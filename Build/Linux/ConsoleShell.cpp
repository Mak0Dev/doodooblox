#include "ConsoleShell.h"

#include <sstream>
#include <strings.h>

#include <boost/thread.hpp>
#include <boost/weak_ptr.hpp>

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#endif

#include "rapidjson/document.h"

#include "FastLog.h"
#include "FunctionMarshaller.h"
#include "network/Player.h"
#include "network/Players.h"
#include "util/Http.h"
#include "util/RunStateOwner.h"
#include "util/SoundService.h"
#include "util/Statistics.h"
#include "util/standardout.h"
#include "v8datamodel/ContentProvider.h"
#include "v8datamodel/DataModel.h"
#include "v8datamodel/Game.h"
#include "v8datamodel/GamepadService.h"
#include "v8datamodel/UserInputService.h"

FASTFLAG(Durango3DBackground)

using namespace RBX;

namespace
{
	// The Xbox app's home: a small world the camera flies through behind the
	// menus (CameraManager.lua). XboxService.cpp's kIntroPlace.
	const char* const kHomePlace = "rbxasset://ScaledWorldv4.7.rbxl";

	// What GetTitleId() answers. The shell keeps a friend "in Roblox" when the
	// title in their presence is this one; the site's friends list for the
	// console (xboxlive/friends) reports this title for friends in a game.
	const int kTitleId = 1;

	// PlatformService.ViewChanged: which game the view shows.
	enum { VIEW_SHELL = 0, VIEW_GAME = 1 };

	std::string siteUrl(const std::string& path)
	{
		std::string base = ::GetBaseURL();
		if (!base.empty() && base[base.size() - 1] != '/')
			base += '/';
		return base + path;
	}

	// The HTTP status: 200 for any success, 0 when nothing came back.
	int httpGet(const std::string& url, std::string& response)
	{
		try
		{
			Http(url).get(response);
			return 200;
		}
		catch (const http_status_error& e)
		{
			return e.statusCode;
		}
		catch (const std::exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_WARNING, "Console: GET %s failed: %s", url.c_str(), e.what());
			return 0;
		}
	}

	int httpPost(const std::string& url, const std::string& body, const std::string& contentType, std::string& response)
	{
		try
		{
			std::istringstream input(body);
			Http(url).post(input, contentType, false, response);
			return 200;
		}
		catch (const http_status_error& e)
		{
			return e.statusCode;
		}
		catch (const std::exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_WARNING, "Console: POST %s failed: %s", url.c_str(), e.what());
			return 0;
		}
	}

	std::string formEncode(const std::string& s)
	{
		static const char hex[] = "0123456789ABCDEF";
		std::string out;
		for (size_t i = 0; i < s.size(); ++i)
		{
			unsigned char c = s[i];
			if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~')
				out += c;
			else
			{
				out += '%';
				out += hex[c >> 4];
				out += hex[c & 15];
			}
		}
		return out;
	}

	// The site answers in camelCase, older endpoints in PascalCase.
	const rapidjson::Value* member(const rapidjson::Value& object, const char* name)
	{
		if (!object.IsObject())
			return NULL;
		for (rapidjson::Value::ConstMemberIterator it = object.MemberBegin(); it != object.MemberEnd(); ++it)
			if (strcasecmp(it->name.GetString(), name) == 0)
				return &it->value;
		return NULL;
	}

	void postToMainThread(const boost::function<void()>& f)
	{
		FunctionMarshaller::Closure* closure = new FunctionMarshaller::Closure;
		closure->f = new boost::function<void()>(f);
		closure->waitEvent = NULL;
		LinuxApp::postAppEvent(closure);
	}

	PlatformService* platformService(DataModel* dm)
	{
		return ServiceProvider::find<PlatformService>(dm);
	}

	// The on-screen keyboard: one at a time, as on the console.
	boost::mutex keyboardMutex;
	boost::weak_ptr<DataModel> keyboardDataModel;
	std::string keyboardDefaultText;

	void keyboardDone(boost::weak_ptr<DataModel> weakDataModel, std::string text)
	{
		shared_ptr<DataModel> dm = weakDataModel.lock();
		if (!dm)
			return;
		dm->submitTask([text](DataModel* dm)
		{
			if (PlatformService* ps = platformService(dm))
				ps->keyboardClosedSignal(text);
		}, DataModelJob::Write);
	}
}

#ifdef __EMSCRIPTEN__
// console.js calls this when its keyboard closes.
extern "C" EMSCRIPTEN_KEEPALIVE void rbxConsoleKeyboardClosed(const char* text, int accepted)
{
	ConsoleShell::keyboardClosed(text ? text : "", accepted != 0);
}
#endif

ConsoleShell::ConsoleShell(const LaunchOptions& options, ShowFunction show)
	: options(options)
	, show(show)
	, shellShown(false)
	, syncing(false)
	, resync(false)
	, userId(0)
{
	// A game the shell starts reports why it did not start, rather than
	// waiting for a full server to have room.
	this->options.waitIfFull = false;
}

ConsoleShell::~ConsoleShell()
{
}

shared_ptr<Game> ConsoleShell::createShell()
{
	// As the Xbox app made its intro: no network, no security - the shell
	// runs its own scripts on a place from disk and joins nothing.
	shell.reset(new UnsecuredStudioGame(NULL, ::GetBaseURL().c_str(), false, false));
	shared_ptr<DataModel> dm = shell->getDataModel();

	{
		DataModel::LegacyLock lock(dm, DataModelJob::Write);

		if (UserInputService* uis = ServiceProvider::create<UserInputService>(dm.get()))
			uis->setGamepadEnabled(true);
		ServiceProvider::create<GamepadService>(dm.get());
		if (PlatformService* ps = ServiceProvider::create<PlatformService>(dm.get()))
		{
			ps->setPlatform(this, AppShellDatamodel);
			// Black until the app's scripts are ready to be seen; they set it back.
			ps->tintColor = Color3(0, 0, 0);
		}
	}

	// The view is made for it right after this (main.cpp), GUI and all.
	shown = viewTarget = shell;
	built.insert(shell.get());
	shellShown = true;
	return shell;
}

void ConsoleShell::startShell()
{
	shell->getDataModel()->submitTask([](DataModel* dm)
	{
		Security::Impersonator impersonate(Security::RobloxGameScript_);

		try
		{
			dm->startCoreScripts(true, "XStarterScript");

			if (FFlag::Durango3DBackground)
				dm->loadContent(ContentId(kHomePlace));

			if (Network::Players* players = dm->create<Network::Players>())
			{
				shared_ptr<Network::Player> player = Instance::fastSharedDynamicCast<Network::Player>(players->createLocalPlayer(1));
				if (player)
					player->loadCharacter(true, "");
			}
			dm->create<RunService>()->run();
			dm->gameLoaded();
		}
		catch (const std::exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_ERROR, "Console: the app did not start: %s", e.what());
		}
	}, DataModelJob::Write);
}

void ConsoleShell::configureGame(shared_ptr<Game> newGame)
{
	shared_ptr<DataModel> dm = newGame->getDataModel();
	DataModel::LegacyLock lock(dm, DataModelJob::Write);

	if (UserInputService* uis = ServiceProvider::create<UserInputService>(dm.get()))
		uis->setGamepadEnabled(true);
	ServiceProvider::create<GamepadService>(dm.get());
	// Games ask the platform too: the in-game menu's blur, Play My Place.
	if (PlatformService* ps = ServiceProvider::create<PlatformService>(dm.get()))
		ps->setPlatform(this, GameDatamodel);
}

// The view moves between games on the main thread, and moving it runs the
// main thread's queue (RobloxView::unbind waits for the render job there). So
// a request is not done in its own queued call: a later one - "close that
// game", for a game that failed to start at once - would run in the middle of
// the view binding to it. Requests set what should be; syncView makes it so,
// in order: first the view, then shutting down what the view has left.
void ConsoleShell::requestView(shared_ptr<Game> target)
{
	{
		boost::mutex::scoped_lock lock(viewMutex);
		viewTarget = target;
	}
	postToMainThread(boost::bind(&ConsoleShell::syncView, this));
}

void ConsoleShell::requestClose(shared_ptr<Game> leaving)
{
	{
		boost::mutex::scoped_lock lock(viewMutex);
		closing.push_back(leaving);
	}
	postToMainThread(boost::bind(&ConsoleShell::syncView, this));
}

void ConsoleShell::syncView()
{
	// Called again from inside itself (the queue runs while the view moves):
	// the outer call picks the change up.
	if (syncing)
	{
		resync = true;
		return;
	}

	syncing = true;
	do
	{
		resync = false;

		shared_ptr<Game> target;
		{
			boost::mutex::scoped_lock lock(viewMutex);
			target = viewTarget;
		}
		if (target && target != shown)
		{
			shown = target;
			shellShown = target == shell;
			show(target, built.insert(target.get()).second);
			muteShell(!shellShown);
		}

		std::vector<shared_ptr<Game> > leaving;
		{
			boost::mutex::scoped_lock lock(viewMutex);
			for (size_t i = 0; i < closing.size(); )
			{
				// Still on screen, or wanted there again: not yet.
				if (closing[i] == shown || closing[i] == viewTarget)
					++i;
				else
				{
					leaving.push_back(closing[i]);
					closing.erase(closing.begin() + i);
				}
			}
		}
		for (size_t i = 0; i < leaving.size(); ++i)
		{
			built.erase(leaving[i].get());
			LinuxApp::relinquishGame(leaving[i]);
		}
	}
	while (resync);
	syncing = false;
}

// The shell keeps running behind a game, and its SoundService is its own FMOD
// system: the menu music (SoundManager's BackgroundLoop) and selection sounds
// would play over the game. The Xbox app suspended the shell; here it is muted.
void ConsoleShell::muteShell(bool mute)
{
	if (!shell)
		return;
	shell->getDataModel()->submitTask([mute](DataModel* dm)
	{
		if (Soundscape::SoundService* sound = ServiceProvider::find<Soundscape::SoundService>(dm))
			sound->muteAllChannels(mute);
	}, DataModelJob::Write);
}

// Callers hold gameMutex, and have taken `leaving` out of `game`.
void ConsoleShell::returnToShell(shared_ptr<Game> leaving)
{
	requestView(shell);
	requestClose(leaving);
	fireViewChanged(VIEW_SHELL);
}

bool ConsoleShell::isGameLoading() const
{
	if (shellShown || !shown)
		return false;
	shared_ptr<DataModel> dm = shown->getDataModel();
	return dm && !dm->getIsGameLoaded();
}

void ConsoleShell::leaveGame()
{
	boost::mutex::scoped_lock lock(gameMutex);
	if (!game)
		return;

	shared_ptr<Game> leaving = game;
	game.reset();
	returnToShell(leaving);
}

void ConsoleShell::teleport(const LaunchOptions& launch)
{
	shared_ptr<Game> next;
	{
		boost::mutex::scoped_lock lock(gameMutex);
		if (!game)
			return;

		shared_ptr<Game> leaving = game;
		next = LinuxApp::createGame();
		configureGame(next);
		game = next;

		requestView(next);
		requestClose(leaving);
	}

	LaunchOptions options = this->options;
	options.authUrl = launch.authUrl;
	options.authTicket = launch.authTicket;
	options.script = launch.script;
	boost::thread(boost::bind(&LinuxApp::launchPlace, next, options));
}

void ConsoleShell::shutdown()
{
	std::vector<shared_ptr<Game> > leaving;
	{
		boost::mutex::scoped_lock lock(gameMutex);
		if (game)
			leaving.push_back(game);
		game.reset();
	}
	{
		boost::mutex::scoped_lock lock(viewMutex);
		leaving.insert(leaving.end(), closing.begin(), closing.end());
		closing.clear();
		viewTarget.reset();
	}
	shown.reset();
	if (shell)
		leaving.push_back(shell);

	for (size_t i = 0; i < leaving.size(); ++i)
		LinuxApp::relinquishGame(leaving[i]);
}

void ConsoleShell::fireViewChanged(int viewType)
{
	shell->getDataModel()->submitTask([viewType](DataModel* dm)
	{
		if (PlatformService* ps = platformService(dm))
			ps->viewChanged(viewType);
	}, DataModelJob::Write);
}

void ConsoleShell::fireGameJoined(int result)
{
	shell->getDataModel()->submitTask([result](DataModel* dm)
	{
		if (PlatformService* ps = platformService(dm))
			ps->gameJoinedSignal(result);
	}, DataModelJob::Write);
}

void ConsoleShell::fireUserAccountChanged(int reason)
{
	shell->getDataModel()->submitTask([reason](DataModel* dm)
	{
		if (PlatformService* ps = platformService(dm))
			ps->userAccountChangeSignal(reason);
	}, DataModelJob::Write);
}

void ConsoleShell::keyboardClosed(const std::string& text, bool accepted)
{
	boost::weak_ptr<DataModel> target;
	std::string result;
	{
		boost::mutex::scoped_lock lock(keyboardMutex);
		target.swap(keyboardDataModel);
		result = accepted ? text : keyboardDefaultText;
	}
	keyboardDone(target, result);
}

//////////////////////////////////////////////////////////////////////////
// Who is playing: the site's session, not an Xbox Live account.

void ConsoleShell::setLocalPlayer(int newUserId, const std::string& name)
{
	{
		boost::mutex::scoped_lock lock(userMutex);
		userId = newUserId;
		userName = name;
	}

	// The app reads who is playing off Players.LocalPlayer, as it did after
	// XboxPlatform::authAndInitUser.
	shell->getDataModel()->submitTask([newUserId, name](DataModel* dm)
	{
		Security::Impersonator impersonate(Security::COM);
		try
		{
			if (Network::Players* players = dm->find<Network::Players>())
			{
				players->resetLocalPlayer();
				if (shared_ptr<Instance> player = players->createLocalPlayer(newUserId))
					player->setName(name);
			}
		}
		catch (const std::exception& e)
		{
			StandardOut::singleton()->printf(MESSAGE_WARNING, "Console: could not set the local player: %s", e.what());
		}
	}, DataModelJob::Write);
}

AccountAuthResult ConsoleShell::readSession()
{
	std::string response;
	int status = httpGet(siteUrl("session/v1"), response);

	// Signed out: the app's sign-in screen, which signs in on the site.
	if (status == 401)
		return AccountAuth_AccountUnlinked;
	if (status == 429)
		return AccountAuth_Flooded;
	if (status != 200)
		return AccountAuth_HttpErrorDetected;

	rapidjson::Document doc;
	doc.Parse<rapidjson::kParseDefaultFlags>(response.c_str());
	const rapidjson::Value* id = doc.HasParseError() ? NULL : member(doc, "id");
	const rapidjson::Value* name = doc.HasParseError() ? NULL : member(doc, "username");
	if (!id || !id->IsInt() || !name || !name->IsString())
		return AccountAuth_Error;

	setLocalPlayer(id->GetInt(), name->GetString());
	return AccountAuth_Success;
}

AccountAuthResult ConsoleShell::performAuthorization(InputObject::UserInputType gamepadId, bool unLinkedCheck)
{
	return readSession();
}

AccountAuthResult ConsoleShell::performHasLinkedAccount()
{
	return readSession();
}

// Accounts made on the console with no password of their own are an Xbox
// thing; every account here has one.
AccountAuthResult ConsoleShell::performHasRobloxCredentials()
{
	return AccountAuth_Success;
}

int ConsoleShell::performSetRobloxCredentials(const std::string& accountName, const std::string& password, std::string* response)
{
	return AccountAuth_Success;
}

// "Link an existing account": signing in to the site. The session cookie it
// sets is the one the rest of the app and every game after it use.
int ConsoleShell::performAccountLink(const std::string& accountName, const std::string& password, std::string* response)
{
	std::string body = "username=" + formEncode(accountName) + "&password=" + formEncode(password);
	std::string result;
	int status = httpPost(siteUrl("login/v1"), body, "application/x-www-form-urlencoded", result);
	if (response)
		*response = result;

	switch (status)
	{
	case 200: return AccountAuth_Success;
	case 400: return AccountAuth_InvalidPassword;
	case 403: return AccountAuth_InvalidRobloxUser; // banned
	case 429: return AccountAuth_Flooded;
	case 0: return AccountAuth_HttpErrorDetected;
	default: return AccountAuth_Error;
	}
}

// "Unlink": signing out. The app goes back to its title screen.
int ConsoleShell::performUnlinkAccount(std::string* response)
{
	std::string result;
	int status = httpPost(siteUrl("logout/v1"), "", "application/json", result);
	if (response)
		*response = result;
	if (status != 200)
		return AccountAuth_HttpErrorDetected;

	{
		boost::mutex::scoped_lock lock(userMutex);
		userId = 0;
		userName.clear();
	}
	fireUserAccountChanged(ReturnToEngage_UnlinkSuccess);
	return AccountAuth_Success;
}

shared_ptr<const Reflection::ValueTable> ConsoleShell::getPlatformUserInfo()
{
	shared_ptr<Reflection::ValueTable> info = rbx::make_shared<Reflection::ValueTable>();
	boost::mutex::scoped_lock lock(userMutex);
	if (userId > 0)
	{
		(*info)["Gamertag"] = userName;
		(*info)["XboxUserId"] = std::string();
		(*info)["RobloxUserName"] = userName;
		(*info)["RobloxUserId"] = userId;
	}
	return info;
}

//////////////////////////////////////////////////////////////////////////
// Games

// Runs on a PlatformService worker and returns when the game has started or
// failed to, as XboxPlatform::startGame3 did. The view shows the game (and its
// loading messages) from the start; a game that does not start is closed and
// the shell comes back with the reason.
GameStartResult ConsoleShell::startGame3(GameJoinType joinType, int id)
{
	std::string base = siteUrl("");
	base.erase(base.size() - 1);

	LaunchOptions launch = options;
	launch.authUrl.clear();
	launch.authTicket.clear();
	switch (joinType)
	{
	case GameJoin_Normal:
	case GameJoin_PMPCreator:
	case GameJoin_PartyLeader:
		launch.script = RBX::format("%s/Game/PlaceLauncher.ashx?request=RequestGame&placeId=%d", base.c_str(), id);
		break;
	case GameJoin_Follow:
		launch.script = RBX::format("%s/Game/PlaceLauncher.ashx?request=RequestFollowUser&userId=%d", base.c_str(), id);
		break;
	default:
		fireGameJoined(GameStart_Weird);
		return GameStart_Weird;
	}

	shared_ptr<Game> newGame;
	{
		boost::mutex::scoped_lock lock(gameMutex);
		if (game)
		{
			fireGameJoined(GameStart_Already);
			return GameStart_Already;
		}

		newGame = LinuxApp::createGame();
		configureGame(newGame);
		game = newGame;

		requestView(newGame);
		fireViewChanged(VIEW_GAME);
	}

	// A local place (console.html?file=, testing with no server) stands in
	// for every game.
	LinuxApp::LaunchResult launched = LinuxApp::LAUNCH_JOINED;
	if (!options.placeFile.empty())
		LinuxApp::openPlaceFile(newGame, options.placeFile);
	else
		launched = LinuxApp::launchPlace(newGame, launch);

	GameStartResult result;
	switch (launched)
	{
	case LinuxApp::LAUNCH_JOINED: result = GameStart_OK; break;
	case LinuxApp::LAUNCH_REFUSED: result = GameStart_NoAccess; break;
	case LinuxApp::LAUNCH_FULL: result = GameStart_Full; break;
	case LinuxApp::LAUNCH_USER_LEFT: result = GameStart_NoPlayer; break;
	case LinuxApp::LAUNCH_CANCELLED: result = GameStart_OK; break;
	default: result = GameStart_WebError; break;
	}

	if (result != GameStart_OK)
	{
		boost::mutex::scoped_lock lock(gameMutex);
		// Not left in the meantime: close it and say why.
		if (game == newGame)
		{
			game.reset();
			returnToShell(newGame);
		}
		else
		{
			// Left while it was loading: the player knows.
			result = GameStart_OK;
		}
	}

	fireGameJoined(result);
	return result;
}

void ConsoleShell::requestGameShutdown(bool teleport)
{
	postToMainThread(boost::bind(&ConsoleShell::leaveGame, this));
}

int ConsoleShell::netConnectionCheck()
{
	return 0;
}

//////////////////////////////////////////////////////////////////////////
// Friends: the site's, shaped like the Xbox Live list FriendsData.lua reads.

int ConsoleShell::fetchFriends(InputObject::UserInputType gamepadId, std::string* result)
{
	std::string response;
	if (httpGet(siteUrl("xboxlive/friends"), response) != 200)
		return -1;
	*result = response;
	return 0;
}

//////////////////////////////////////////////////////////////////////////
// The console's own UI. A browser has none of it; the few that matter are
// the page's (console.js).

int ConsoleShell::popupHelpUI()
{
	return launchPlatformUri(siteUrl("help"));
}

int ConsoleShell::launchPlatformUri(const std::string baseUri)
{
#ifdef __EMSCRIPTEN__
	struct Open
	{
		static void run(std::string uri)
		{
			EM_ASM({ if (Module['consoleOpenUri']) Module['consoleOpenUri'](UTF8ToString($0)); }, uri.c_str());
		}
	};
	postToMainThread(boost::bind(&Open::run, baseUri));
#endif
	return 0;
}

int ConsoleShell::popupPartyUI(InputObject::UserInputType gamepadId)
{
	return 0;
}

int ConsoleShell::popupProfileUI(InputObject::UserInputType gamepadId, std::string uid)
{
	return 0;
}

int ConsoleShell::popupAccountPickerUI(InputObject::UserInputType gamepadId)
{
	return 0;
}

void ConsoleShell::popupGameInviteUI()
{
}

// The Xbox's virtual keyboard: the page shows one over the game and calls
// rbxConsoleKeyboardClosed with what was typed.
void ConsoleShell::showKeyBoard(std::string& title, std::string& description, std::string& defaultText, unsigned keyboardType, DataModel* dm)
{
	{
		boost::mutex::scoped_lock lock(keyboardMutex);
		if (!keyboardDataModel.expired())
			return;
		keyboardDataModel = weak_from(dm);
		keyboardDefaultText = defaultText;
	}

#ifdef __EMSCRIPTEN__
	struct Show
	{
		static void run(std::string title, std::string description, std::string text, unsigned type)
		{
			int shown = EM_ASM_INT({
				if (!Module['consoleShowKeyboard'])
					return 0;
				Module['consoleShowKeyboard'](UTF8ToString($0), UTF8ToString($1), UTF8ToString($2), $3 == 3);
				return 1;
			}, title.c_str(), description.c_str(), text.c_str(), type);
			if (!shown)
				ConsoleShell::keyboardClosed(text, false);
		}
	};
	postToMainThread(boost::bind(&Show::run, title, description, defaultText, keyboardType));
#else
	keyboardClosed(defaultText, false);
#endif
}

void ConsoleShell::setScreenResolution(double px, double py)
{
}

//////////////////////////////////////////////////////////////////////////
// The Xbox store: Robux bought with the console's money. Not here.

int ConsoleShell::fetchCatalogInfo(shared_ptr<Reflection::ValueArray> values)
{
	return 0;
}

int ConsoleShell::fetchInventoryInfo(shared_ptr<Reflection::ValueArray> values)
{
	return 0;
}

PlatformPurchaseResult ConsoleShell::requestPurchase(const std::string& productId)
{
	return PurchaseResult_Error;
}

int ConsoleShell::getPlatformPartyMembers(shared_ptr<Reflection::ValueArray> result)
{
	return 0;
}

int ConsoleShell::getInGamePlayers(shared_ptr<Reflection::ValueArray> result)
{
	return 0;
}

int ConsoleShell::getPMPCreatorId()
{
	return 0;
}

int ConsoleShell::getTitleId()
{
	return kTitleId;
}

shared_ptr<const Reflection::ValueTable> ConsoleShell::getVersionIdInfo()
{
	shared_ptr<Reflection::ValueTable> version = rbx::make_shared<Reflection::ValueTable>();
	(*version)["Major"] = 1;
	(*version)["Minor"] = 0;
	(*version)["Build"] = 0;
	(*version)["Revision"] = 0;
	return version;
}

AwardResult ConsoleShell::awardAchievement(const std::string& eventName)
{
	return Award_OK;
}

AwardResult ConsoleShell::setHeroStat(const std::string& eventName, double* value)
{
	return Award_OK;
}

void ConsoleShell::voiceChatSetMuteState(int userId, bool mute)
{
}

unsigned ConsoleShell::voiceChatGetState(int userId)
{
	return voiceChatState_NotInChat;
}
