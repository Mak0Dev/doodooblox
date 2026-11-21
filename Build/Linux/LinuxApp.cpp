#include "LinuxApp.h"
#include "SDLGameController.h"

#include <algorithm>
#include <cstdio>
#include <deque>
#include <fstream>
#include <sstream>

#include <boost/iostreams/copy.hpp>
#include <boost/thread.hpp>

#include <SDL.h>

#include "rapidjson/document.h"

#include "FastLog.h"
#include "rbx/Profiler.h"
#include "rbx/SystemUtil.h"
#include "rbx/TaskScheduler.h"
#include "network/Players.h"
#include "script/ScriptContext.h"
#include "util/Http.h"
#include "util/Statistics.h"
#include "util/SoundService.h"
#include "util/standardout.h"
#include "v8datamodel/ContentProvider.h"
#include "v8datamodel/DataModel.h"
#include "v8datamodel/DebugSettings.h"
#include "v8datamodel/FastLogSettings.h"
#include "v8datamodel/Game.h"
#include "v8datamodel/GameBasicSettings.h"
#include "v8datamodel/GlobalSettings.h"
#include "v8datamodel/GuiService.h"
#include "v8datamodel/TeleportService.h"
#include "v8datamodel/TeleportCallback.h"
#include "v8datamodel/UserInputService.h"
#include "v8datamodel/Workspace.h"
#include "v8datamodel/Camera.h"
#include "v8datamodel/ModelInstance.h"
#include "script/ScriptContext.h"
#include "v8xml/Serializer.h"
#include "script/LuaSourceContainer.h"

LOGGROUP(PlayerShutdownLuaTimeoutSeconds)
FASTINTVARIABLE(LinuxRequestPlaceInfoRetryCount, 60)
FASTINTVARIABLE(LinuxRequestPlaceInfoTimeoutMS, 2000)

using namespace RBX;

namespace
{
	boost::mutex eventMutex;
	boost::condition_variable eventPosted;
	std::deque<FunctionMarshaller::Closure*> events;
	bool renderPrepared = false;
	bool framePresented = false;
	bool eventsRunning = true;
	Uint32 wakeEvent = (Uint32)-1;

	volatile bool quitFlag = false;
	volatile bool pageHidden = false;

	rbx::signals::scoped_connection messageOutConnection;
	boost::thread releaseGameThread;

	void onFastLog(FLog::Channel channel, const char* message)
	{
		fprintf(stderr, "[FLog] %s\n", message);
	}

	void onMessageOut(const StandardOutMessage& message)
	{
		const char* prefix = "";
		switch (message.type)
		{
		case MESSAGE_INFO:    prefix = "INFO: "; break;
		case MESSAGE_WARNING: prefix = "WARNING: "; break;
		case MESSAGE_ERROR:   prefix = "ERROR: "; break;
		default: break;
		}
		fprintf(stderr, "%s%s\n", prefix, message.message.c_str());
	}

	void setUiMessage(shared_ptr<Game> game, const std::string& message)
	{
		shared_ptr<DataModel> dm = game->getDataModel();
		if (!dm)
			return;

		DataModel::LegacyLock lock(dm, DataModelJob::Write);
		if (message.empty())
			dm->clearUiMessage();
		else
			dm->setUiMessage(message);
	}

	// A teleport replaces the running game; the shell owns that, so hand it to
	// the main thread.
	class Teleporter : public TeleportCallback
	{
	public:
		virtual void doTeleport(const std::string& url, const std::string& ticket, const std::string& script)
		{
			LaunchOptions* options = new LaunchOptions();
			options->authUrl = url;
			options->authTicket = ticket;
			options->script = script;

			FunctionMarshaller::Closure* closure = new FunctionMarshaller::Closure;
			closure->f = new boost::function<void()>(boost::bind(&Teleporter::requestTeleport, options));
			closure->waitEvent = NULL;
			LinuxApp::postAppEvent(closure);
		}

		virtual bool isTeleportEnabled() const { return true; }

		static boost::function<void(const LaunchOptions&)> onTeleport;

	private:
		static void requestTeleport(LaunchOptions* options)
		{
			boost::scoped_ptr<LaunchOptions> owned(options);
			if (onTeleport)
				onTeleport(*owned);
		}
	};
	boost::function<void(const LaunchOptions&)> Teleporter::onTeleport;
	Teleporter teleporter;

	std::string readJsonString(const rapidjson::Document& doc, const char* name)
	{
		if (doc.HasMember(name) && doc[name].IsString())
			return doc[name].GetString();
		return "";
	}

	void authenticate(const std::string& authUrl, const std::string& ticket)
	{
		// Negotiate has nothing to redeem without a ticket.  In the browser that
		// is the normal case: the site's session cookie is already on every
		// request, and PlaceLauncher reads the player from it.
		if (authUrl.empty() || ticket.empty())
			return;

		std::string url = authUrl + (authUrl.find('?') == std::string::npos ? "?suggest=" : "&suggest=") + ticket;

		try
		{
			std::string result;
			Http http(url);
			http.setAuthDomain(::GetBaseURL());
			http.get(result);
		}
		catch (const base_exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_WARNING, "Authentication failed: %s", e.what());
		}
	}

	ProtectedString fetchAndValidateScript(DataModel* dataModel, const std::string& urlScript)
	{
		Security::Impersonator impersonate(Security::COM);

		std::ostringstream data;
		if (!ContentProvider::isUrl(urlScript))
			return ProtectedString();

		{
			DataModel::LegacyLock lock(dataModel, DataModelJob::Write);
			std::auto_ptr<std::istream> stream(ServiceProvider::create<ContentProvider>(dataModel)->getContent(ContentId(urlScript.c_str())));
			boost::iostreams::copy(*stream, data);
		}

		try
		{
			ProtectedString verifiedSource = ProtectedString::fromTrustedSource(data.str());
			ContentProvider::verifyScriptSignature(verifiedSource, true);
			return verifiedSource;
		}
		catch (std::bad_alloc&)
		{
			throw;
		}
		catch (base_exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_ERROR, "Join script rejected: %s", e.what());
			return ProtectedString();
		}
	}

	LinuxApp::LaunchResult executeJoinScript(shared_ptr<Game> game, const std::string& urlScript)
	{
		shared_ptr<DataModel> dataModel = game->getDataModel();
		if (!dataModel)
			return LinuxApp::LAUNCH_CANCELLED;

		ProtectedString verifiedSource = fetchAndValidateScript(dataModel.get(), urlScript);
		if (verifiedSource.empty())
		{
			setUiMessage(game, "Failed to load the join script.");
			return LinuxApp::LAUNCH_FAILED;
		}

		Security::Impersonator impersonate(Security::COM);
		DataModel::LegacyLock lock(dataModel, DataModelJob::Write);

		if (dataModel->isClosed())
			return LinuxApp::LAUNCH_CANCELLED;

		// The current join script is a signature line followed by JSON.
		std::string source = verifiedSource.getSource();
		size_t firstNewLine = source.find("\r\n");
		if (firstNewLine != std::string::npos && firstNewLine + 2 < source.size() && source[firstNewLine + 2] == '{')
		{
			// 1 is SharedLauncher::LaunchMode Play from a protocol link, as on Windows.
			game->configurePlayer(Security::COM, source.substr(firstNewLine + 2), 1);
			return LinuxApp::LAUNCH_JOINED;
		}

		ScriptContext* context = dataModel->create<ScriptContext>();
		context->executeInNewThread(Security::COM, verifiedSource, "Start Game");
		return LinuxApp::LAUNCH_JOINED;
	}

	enum PlaceInfoResult { PLACE_SUCCESS, PLACE_FAILED, PLACE_RETRY, PLACE_SERVER_READY, PLACE_GAME_FULL, PLACE_USER_LEFT, PLACE_REFUSED };

	PlaceInfoResult requestPlaceInfo(const std::string& url, std::string& joinScriptUrl, int& status)
	{
		status = -1;
		try
		{
			std::string response;
			Http(url).get(response);

			rapidjson::Document doc;
			doc.Parse<rapidjson::kParseDefaultFlags>(response.c_str());
			if (doc.HasParseError() || !doc.IsObject() || !doc.HasMember("status") || !doc["status"].IsInt())
				return PLACE_FAILED;

			// Waiting = 0, Loading = 1, Joining = 2, Disabled = 3, Error = 4,
			// GameEnded = 5, GameFull = 6, UserLeft = 10, Restricted = 11
			status = doc["status"].GetInt();
			switch (status)
			{
			case 0: return PLACE_RETRY;
			case 1: return PLACE_SERVER_READY;
			case 2:
				joinScriptUrl = readJsonString(doc, "joinScriptUrl");
				return joinScriptUrl.empty() ? PLACE_FAILED : PLACE_SUCCESS;
			case 6: return PLACE_GAME_FULL;
			case 10: return PLACE_USER_LEFT;
			default: return PLACE_REFUSED;
			}
		}
		catch (base_exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_ERROR, "Exception when requesting place info: %s", e.what());
		}
		return PLACE_FAILED;
	}

	bool containsNoCase(const std::string& haystack, const char* needle)
	{
		std::string h = haystack;
		std::string n = needle;
		std::transform(h.begin(), h.end(), h.begin(), ::tolower);
		std::transform(n.begin(), n.end(), n.begin(), ::tolower);
		return h.find(n) != std::string::npos;
	}
}

void LinuxApp::postAppEvent(FunctionMarshaller::Closure* closure)
{
	{
		boost::mutex::scoped_lock lock(eventMutex);
		events.push_back(closure);
	}
	eventPosted.notify_all();

	if (wakeEvent != (Uint32)-1)
	{
		SDL_Event event;
		SDL_zero(event);
		event.type = wakeEvent;
		SDL_PushEvent(&event);
	}
}

void LinuxApp::processAppEvents()
{
	for (;;)
	{
		FunctionMarshaller::Closure* closure = NULL;
		bool running;
		{
			boost::mutex::scoped_lock lock(eventMutex);
			if (events.empty())
				return;
			closure = events.front();
			events.pop_front();
			running = eventsRunning;
		}

		if (running)
		{
			FunctionMarshaller::handleAppEvent(closure);
		}
		else
		{
			// Still release whoever is blocked in Execute on it.
			CEvent* waitEvent = closure->waitEvent;
			FunctionMarshaller::freeAppEvent(closure);
			if (waitEvent)
				waitEvent->Set();
		}
	}
}

void LinuxApp::onRenderPrepared()
{
	renderPrepared = true;
}

void LinuxApp::onFramePresented()
{
	framePresented = true;
}

void LinuxApp::beginFrame()
{
	renderPrepared = false;
	framePresented = false;
}

void LinuxApp::finishFrame(double budgetMs)
{
	const boost::posix_time::ptime deadline = boost::posix_time::microsec_clock::universal_time()
		+ boost::posix_time::microseconds((long) (budgetMs * 1000));

	while (renderPrepared && !framePresented)
	{
		{
			boost::mutex::scoped_lock lock(eventMutex);
			if (events.empty() && !eventPosted.timed_wait(lock, deadline))
				return;
		}
		processAppEvents();
	}
}

void LinuxApp::setPageHidden(bool hidden)
{
	pageHidden = hidden;
}

bool LinuxApp::isPageHidden()
{
	return pageHidden;
}

void LinuxApp::setEventsRunning(bool running)
{
	boost::mutex::scoped_lock lock(eventMutex);
	eventsRunning = running;
}

unsigned LinuxApp::wakeEventType()
{
	if (wakeEvent == (Uint32)-1)
		wakeEvent = SDL_RegisterEvents(1);
	return wakeEvent;
}

bool LinuxApp::globalInit(const LaunchOptions& options)
{
	messageOutConnection = StandardOut::singleton()->messageOut.connect(&onMessageOut);

	::SetBaseURL(options.baseUrl);
	ContentProvider::setAssetFolder(options.contentFolder.c_str());

	if (options.console)
	{
		// The Xbox app's textures, sounds and home place, over the shared
		// content: PlatformContent/console next to PlatformContent/pc.
		ContentProvider::setOverlayAssetFolder((options.contentFolder + "../PlatformContent/console").c_str());
		UserInputService::setConsolePlatform(true);
		SDLGameController::keyboardGamepad = true;
	}

	Http::rbxUserAgent = "Roblox/Linux";
	Game::globalInit(false);
	TeleportService::SetBaseUrl(::GetBaseURL().c_str());
	TeleportService::SetCallback(&teleporter);

	// FastFlags: the shared ClientAppSettings group, then this platform's own
	// on top of it, as Android does with AndroidAppSettings. Both come from
	// Setting/QuietGet/<group> on the site (clientsettings.api.<domain>); an
	// unreachable site leaves the built-in defaults.
	ClientAppSettings::Initialize();
#ifdef RBX_PLATFORM_WEB
	FetchClientSettingsData("WebAppSettings", CLIENT_SETTINGS_API_KEY, &ClientAppSettings::singleton());
#else
	FetchClientSettingsData("LinuxAppSettings", CLIENT_SETTINGS_API_KEY, &ClientAppSettings::singleton());
#endif

	// Reset synchronized flags, they should be set by the server.
	FLog::ResetSynchronizedVariablesState();

	// Local overrides win over the site's settings; any of them also turns on
	// printing FastLog to stderr.
	for (size_t i = 0; i < options.flogs.size(); ++i)
	{
		const std::string& item = options.flogs[i];
		size_t eq = item.find('=');
		std::string name = item.substr(0, eq);
		std::string value = eq == std::string::npos ? "1" : item.substr(eq + 1);
		if (!FLog::SetValue(name, value))
			fprintf(stderr, "Unknown fast variable: %s\n", name.c_str());
	}
	if (!options.flogs.empty())
		FLog::SetExternalLogFunc(&onFastLog);

	// The game server checks this against its list of allowed client hashes.
	// Linux has no signed executable to hash, so it identifies the way the
	// mobile clients do unless told otherwise.
	DataModel::hash = options.hash.empty() ? std::string("ios,ios") : options.hash;

	Http::SetUseStatistics(true);

	GlobalAdvancedSettings::singleton()->loadState("");
	{
		Security::Impersonator impersonate(Security::RobloxGameScript_);
		GlobalBasicSettings::singleton()->loadState("");

		// The browser keeps no settings file between visits; the page passes
		// the frame rate cap it remembered.
		if (options.framerateCap >= GameBasicSettings::FRAMERATE_CAP_60 && options.framerateCap <= GameBasicSettings::FRAMERATE_CAP_UNLIMITED)
			GameBasicSettings::singleton().setFramerateCap((GameBasicSettings::FramerateCap) options.framerateCap);

		// The same for the voice chat switch in the settings menu.
		if (options.hearVoiceChat >= 0)
			GameBasicSettings::singleton().setHearVoiceChat(options.hearVoiceChat != 0);
	}

	Profiler::onThreadCreate("Main");

	// Initialize the TaskScheduler (after loading configs).
	TaskScheduler::singleton().setThreadCount(TaskSchedulerSettings::singleton().getThreadPoolConfig());

	return true;
}

void LinuxApp::globalShutdown()
{
	// Don't exit until Game has been shut down. Otherwise we might get crashes
	// in static destructors.
	releaseGameThread.join();

	GlobalBasicSettings::singleton()->saveState();

	Game::globalExit();
	messageOutConnection.disconnect();
}

shared_ptr<Game> LinuxApp::createGame()
{
	shared_ptr<Game> game(new SecurePlayerGame(NULL, ::GetBaseURL().c_str()));

	if (FLog::PlayerShutdownLuaTimeoutSeconds > 0)
		game->getDataModel()->create<ScriptContext>();

	return game;
}

static void releaseGame(shared_ptr<Game> game)
{
	GlobalBasicSettings::singleton()->saveState();
	game->shutdown();
}

void LinuxApp::relinquishGame(shared_ptr<Game>& game)
{
	releaseGameThread.join();

	// Defer deletion of the DataModel so that the main thread does not block.
	releaseGameThread = boost::thread(boost::bind(&releaseGame, game));

	game.reset();
}

static LinuxApp::LaunchResult launchPlaceSteps(shared_ptr<Game> game, const LaunchOptions& options);

// Runs on a thread of its own. An exception out of it - a join script that
// would not load, say - reaches std::terminate and takes the whole player down
// (in the browser: "The game stopped"); the player gets a message instead.
LinuxApp::LaunchResult LinuxApp::launchPlace(shared_ptr<Game> game, const LaunchOptions& options)
{
	std::string failure;
	try
	{
		return launchPlaceSteps(game, options);
	}
	catch (const std::exception& e)
	{
		failure = e.what();
	}
	catch (...)
	{
		failure = "unknown error";
	}

	StandardOut::singleton()->printf(MESSAGE_ERROR, "Joining the game failed: %s", failure.c_str());
	try
	{
		setUiMessage(game, "Failed to join the game. Please try again.");
	}
	catch (...)
	{
	}
	return LAUNCH_FAILED;
}

static LinuxApp::LaunchResult launchPlaceSteps(shared_ptr<Game> game, const LaunchOptions& options)
{
	// A launch ticket is spent on Negotiate first, so every request after it -
	// PlaceLauncher included - carries the session cookie.
	authenticate(options.authUrl, options.authTicket);

	if (!containsNoCase(options.script, "PlaceLauncher.ashx"))
	{
		setUiMessage(game, "Loading...");
		return executeJoinScript(game, options.script);
	}

	setUiMessage(game, "Requesting Server...");

	int retries = FInt::LinuxRequestPlaceInfoRetryCount;
	while (retries >= 0 && !quitFlag)
	{
		shared_ptr<DataModel> dm = game->getDataModel();
		if (!dm || dm->isClosed())
			return LinuxApp::LAUNCH_CANCELLED;

		std::string joinScriptUrl;
		int status = -1;
		switch (requestPlaceInfo(options.script, joinScriptUrl, status))
		{
		case PLACE_SUCCESS:
			setUiMessage(game, "Joining server...");
			return executeJoinScript(game, joinScriptUrl);
		case PLACE_FAILED:
			retries--;
			break;
		case PLACE_RETRY:
			break;
		case PLACE_SERVER_READY:
			setUiMessage(game, "Server found. Connecting...");
			break;
		case PLACE_GAME_FULL:
			if (!options.waitIfFull)
				return LinuxApp::LAUNCH_FULL;
			setUiMessage(game, "The game you requested is currently full. Waiting for an opening...");
			break;
		case PLACE_USER_LEFT:
			setUiMessage(game, "The user has left the game.");
			return LinuxApp::LAUNCH_USER_LEFT;
		case PLACE_REFUSED:
			setUiMessage(game, RBX::format("The game could not be started (status %d).", status));
			return LinuxApp::LAUNCH_REFUSED;
		}

		boost::this_thread::sleep(boost::posix_time::milliseconds(FInt::LinuxRequestPlaceInfoTimeoutMS));
	}

	setUiMessage(game, "Failed to request game, please try again.");
	return LinuxApp::LAUNCH_FAILED;
}

// The player runs only server-compiled bytecode, so a place opened locally has
// none of its scripts - CameraScript included - and a Custom camera nobody
// moves. Follow is one the engine runs itself: it keeps the character in
// view and turns with the right mouse button. Local testing only; in a real
// game the server's CameraScript drives the camera.
static void followCharacter(DataModel* dataModel, Network::Player* player)
{
	ModelInstance* character = player->getCharacter();
	Camera* camera = dataModel->getWorkspace()->getCamera();
	if (!character || !camera)
		return;

	const G3D::Vector3 at = character->getPrimaryCFrame().translation;
	CoordinateFrame eye(at + G3D::Vector3(0, 10, 18));
	eye.lookAt(at);

	camera->setCameraType(Camera::FOLLOW_CAMERA);
	camera->setCameraFocus(CoordinateFrame(at));
	camera->setCameraCoordinateFrame(eye);
}

void LinuxApp::openPlaceFile(shared_ptr<Game> game, const std::string& path)
{
	shared_ptr<DataModel> dataModel = game->getDataModel();
	if (!dataModel)
		return;

	try
	{
		// As Studio opens a local file: ContentProvider only reads file:// in test
		// builds, and a bare path would be fetched over HTTP.
		//
		// Read whole, then parsed from memory. In the browser every file call
		// from this thread is a round trip to the page's thread, and the
		// parser reads a few bytes at a time: a 7 MB place took a minute and
		// more to open, where one read takes milliseconds. It is also read
		// before the DataModel is locked, so the loading screen keeps drawing.
		std::string contents;
		{
			std::ifstream file(path.c_str(), std::ios_base::in | std::ios_base::binary);
			if (!file)
				throw RBX::runtime_error("Cannot open %s", path.c_str());
			file.seekg(0, std::ios_base::end);
			contents.resize((size_t) std::max<std::streamoff>(0, file.tellg()));
			file.seekg(0, std::ios_base::beg);
			if (!contents.empty())
				file.read(&contents[0], contents.size());
		}
		std::istringstream stream(contents);

		Security::Impersonator impersonate(Security::COM);
		DataModel::LegacyLock lock(dataModel, DataModelJob::Write);

		if (dataModel->isClosed())
			return;

		Serializer().load(stream, dataModel.get());
		LuaSourceContainer::blockingLoadLinkedScripts(dataModel->create<ContentProvider>(), dataModel.get());
		dataModel->processAfterLoad();
		dataModel->getWorkspace()->createTerrain();
		// What DataModel::loadContent announces when it is done. StarterPlayer
		// waits for it to create StarterPlayerScripts, which is where the
		// default CameraScript and ControlScript load - without it the player
		// had no camera control and could not walk.
		dataModel->workspaceLoadedSignal();

		// Play Solo's order (visit.lua): run first - that is what loads the
		// default CameraScript and ControlScript into StarterPlayerScripts -
		// then create the player, who gets copies of them, then its character.
		// With the player created first it had neither: no camera control, no
		// walking.
		if (RunService* runService = ServiceProvider::create<RunService>(dataModel.get()))
			runService->run();

		// Core scripts are the view's to start (RobloxView::bindToWorkspace),
		// as in a game joined online: it builds the in-game GUI they need
		// first. Started from here too, they could beat the console's view to
		// it, and the settings menu waited forever for RobloxGui.ControlFrame.

		if (Network::Players* players = ServiceProvider::create<Network::Players>(dataModel.get()))
		{
			players->createLocalPlayer(0);
			if (Network::Player* player = players->getLocalPlayer())
			{
				player->loadCharacter(false, "");
				followCharacter(dataModel.get(), player);
			}
		}

		dataModel->setIsGameLoaded(true);
	}
	catch (base_exception& e)
	{
		StandardOut::singleton()->printf(MESSAGE_ERROR, "Failed to open %s: %s", path.c_str(), e.what());
	}
}

void LinuxApp::setTeleportHandler(boost::function<void(const LaunchOptions&)> handler)
{
	Teleporter::onTeleport = handler;
}

void LinuxApp::requestQuit()
{
	quitFlag = true;
	SDL_Event event;
	SDL_zero(event);
	event.type = SDL_QUIT;
	SDL_PushEvent(&event);
}

bool LinuxApp::quitRequested()
{
	return quitFlag;
}
