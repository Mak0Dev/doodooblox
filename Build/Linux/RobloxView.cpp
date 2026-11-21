#include "RobloxView.h"

#include <SDL.h>

#include "FunctionMarshaller.h"
#include "LinuxApp.h"
#include "UserInput.h"

#include "GfxBase/FrameRateManager.h"
#include "GfxBase/RenderSettings.h"
#include "GfxBase/ViewBase.h"
#include "RenderSettingsItem.h"
#include "rbx/SystemUtil.h"
#include "rbx/Tasks/Coordinator.h"
#include "script/ScriptContext.h"
#include "util/IMetric.h"
#include "util/standardout.h"
#include "v8datamodel/BaseRenderJob.h"
#include "v8datamodel/DataModel.h"
#include "v8datamodel/GameBasicSettings.h"
#include "v8datamodel/UserController.h"

LOGGROUP(PlayerShutdownLuaTimeoutSeconds)

using namespace RBX;

namespace
{
	// Verbs the in-game menu looks up by name.
	class CallbackVerb : public Verb
	{
		boost::function<void()> callback;
		bool enabled;
	public:
		CallbackVerb(VerbContainer* container, const char* name, boost::function<void()> callback, bool enabled = true)
			: Verb(container, name)
			, callback(callback)
			, enabled(enabled)
		{
		}

		virtual bool isEnabled() const { return enabled; }

		virtual void doIt(IDataState*)
		{
			if (callback)
				callback();
		}
	};
}

// Calls ViewBase::render(), which must be exclusive to the DataModel (hence
// DataModelJob::Render) and must run on the thread that owns the GL context,
// so the render itself is marshalled to the main thread.
class RobloxView::RenderJob : public BaseRenderJob, public IMetric
{
	FunctionMarshaller* marshaller;
	ViewBase* view;
	CEvent renderEvent;

public:
	RenderJob(ViewBase* view, FunctionMarshaller* marshaller, shared_ptr<DataModel> dataModel)
		: BaseRenderJob(CRenderSettingsItem::singleton().getMinFrameRate(), CRenderSettingsItem::singleton().getMaxFrameRate(), dataModel)
		, marshaller(marshaller)
		, view(view)
		, renderEvent(false)
	{
		cyclicExecutive = true;
	}

	// While the web page is hidden there is nobody to draw for; a few steps a
	// second keep RenderStepped, the camera and input moving without the
	// frames nobody would see.
	static const double hiddenFrameRate;

	Time::Interval sleepTime(const Stats& stats)
	{
		if (LinuxApp::isPageHidden())
			return computeStandardSleepTime(stats, hiddenFrameRate);
		if (isAwake)
			return computeStandardSleepTime(stats, CRenderSettingsItem::singleton().getMaxFrameRate());
		else
			return Time::Interval::max();
	}

	Job::Error error(const Stats& stats)
	{
		if (LinuxApp::isPageHidden())
			return computeStandardError(stats, hiddenFrameRate);
		return BaseRenderJob::error(stats);
	}

	static void scheduleRenderPrepare(ViewBase* view, RenderJob* self)
	{
		view->renderPrepare(self);
		LinuxApp::onRenderPrepared();
	}

	static void scheduleRenderPerform(const weak_ptr<RenderJob>& selfWeak, ViewBase* view, double timeJobStart)
	{
		if (shared_ptr<RenderJob> self = selfWeak.lock())
		{
			view->renderPerform(timeJobStart);
			LinuxApp::onFramePresented();
			self->wake();
		}
	}

	virtual TaskScheduler::StepResult stepDataModelJob(const Stats& stats)
	{
		DataModel* dm = view->getDataModel();
		if (!dm)
			return TaskScheduler::Done;

		double timeJobStart = Time::nowFastSec();
		try
		{
			const float secondsElapsed = view->getFrameRateManager()->GetFrameTimeStats().getLatest() / 1000.f;
			lastRenderTime = Time::now<Time::Fast>();

			DataModel::scoped_write_request request(dm);
			dm->renderStep(secondsElapsed);

			if (LinuxApp::isPageHidden())
				return TaskScheduler::Stepped;

			isAwake = false;
			marshaller->Execute(boost::bind(&RenderJob::scheduleRenderPrepare, view, this), &renderEvent);

			marshaller->Submit(boost::bind(&scheduleRenderPerform, weak_from(this), view, timeJobStart));
		}
		catch (std::exception& e)
		{
			StandardOut::singleton()->print(MESSAGE_ERROR, e);
		}
		return TaskScheduler::Stepped;
	}

	void abortRender()
	{
		renderEvent.Set();
	}

	/*override*/ double getMetricValue(const std::string& metric) const
	{
		FrameRateManager* frm = view ? view->getFrameRateManager() : 0;

		if (metric == "Render FPS")
			return averageStepsPerSecond();
		if (metric == "Render Duty")
			return averageDutyCycle();
		if (metric == "Render Nominal FPS")
			return frm ? 1000.0 / frm->GetRenderTimeAverage() : 0.0;
		if (metric == "Delta Between Renders" || metric == "Total Render" || metric == "Present Time" || metric == "GPU Delay")
			return view->getMetricValue(metric);
		if (metric == "Video Memory")
			return (double)SystemUtil::getVideoMemory();

		return 0.0;
	}

	/*override*/ std::string getMetric(const std::string& metric) const
	{
		if (!view)
			return "No View";

		if (metric == "Graphics Mode")
			return "OpenGL ES";

		FrameRateManager* frm = view->getFrameRateManager();

		if (metric == "FRM")
			return (frm && frm->IsBlockCullingEnabled()) ? "On" : "Off";
		if (metric == "Anti-Aliasing")
			return (frm && frm->getAntialiasingMode() == CRenderSettings::AntialiasingOn) ? "On" : "Off";

		return "";
	}
};

const double RobloxView::RenderJob::hiddenFrameRate = 10.0;

static ViewBase* createGameWindow(SDL_Window* window, unsigned width, unsigned height)
{
	static boost::once_flag flag = BOOST_ONCE_INIT;
	boost::call_once(&ViewBase::InitPluginModules, flag);

	OSContext context;
	context.hWnd = window;
	context.width = width;
	context.height = height;

	CRenderSettings& settings = CRenderSettingsItem::singleton();
	ViewBase* rbxView = ViewBase::CreateView(CRenderSettings::OpenGL, &context, &settings);
	rbxView->initResources();

	return rbxView;
}

RobloxView::RobloxView(SDL_Window* window, RobloxViewHost* host, shared_ptr<Game> game, unsigned width, unsigned height,
	bool ownsGame, bool buildGui)
	: width(width)
	, height(height)
	, window(window)
	, host(host)
	, view(createGameWindow(window, width, height))
	, game(game)
	, ownsGame(ownsGame)
	, marshaller(FunctionMarshaller::GetWindow())
	, cursorPos(width / 2.0f, height / 2.0f)
{
	view->onResize(width, height);
	bind(buildGui);
}

RobloxView::~RobloxView()
{
	unbind(ownsGame);

	FunctionMarshaller::ReleaseWindow(marshaller);

	// Destroy the view before closing the DataModel.
	view.reset();

	if (ownsGame)
		LinuxApp::relinquishGame(game);
}

// Not re-entered from the main thread's queue: unbinding drains it (stopJobs),
// so the caller must not have anything queued that closes newGame
// (ConsoleShell::syncView).
void RobloxView::setGame(shared_ptr<Game> newGame, bool buildGui)
{
	if (newGame == game)
		return;

	unbind(false);
	game = newGame;
	bind(buildGui);
}

void RobloxView::bind(bool buildGui)
{
	shared_ptr<DataModel> dataModel = game->getDataModel();

	userInput.reset(new UserInput(this, game));

	leaveGameVerb.reset(new CallbackVerb(dataModel.get(), "Exit", boost::bind(&RobloxView::leaveGame, this)));
	fullscreenVerb.reset(new CallbackVerb(dataModel.get(), "ToggleFullScreen", boost::bind(&RobloxView::toggleFullScreen, this)));
	studioVerb.reset(new CallbackVerb(dataModel.get(), "TogglePlayMode", boost::function<void()>(), false));
	screenshotVerb.reset(new CallbackVerb(dataModel.get(), "Screenshot", boost::function<void()>(), false));
	shutdownClientVerb.reset(new CallbackVerb(dataModel.get(), "ShutdownClient", boost::bind(&RobloxView::leaveGame, this)));

	bindToWorkspace(buildGui);

	renderJob.reset(new RenderJob(view.get(), marshaller, dataModel));

	defineConcurrencyRules();

	// Only schedule the render job after the concurrency rules are defined.
	TaskScheduler::singleton().add(renderJob);
}

// closing: the game is being shut down too, so its scripts get a deadline.
// Otherwise it only leaves the view and runs on.
void RobloxView::unbind(bool closing)
{
	if (sequence)
	{
		if (shared_ptr<DataModel> dataModel = game->getDataModel())
			if (RunService* rs = dataModel->find<RunService>())
				rs->getPhysicsJob()->removeCoordinator(sequence);
		sequence.reset();
	}

	stopJobs();

	if (shared_ptr<DataModel> dataModel = game->getDataModel())
	{
		if (closing)
		{
			// Give scripts a deadline to finish.
			if (FLog::PlayerShutdownLuaTimeoutSeconds > 0)
				if (ScriptContext* scriptContext = dataModel->find<ScriptContext>())
					scriptContext->setTimeout(FLog::PlayerShutdownLuaTimeoutSeconds);
			dataModel->setIsShuttingDown(true);
		}

		DataModel::LegacyLock lock(dataModel, DataModelJob::Write);

		ControllerService* service = ServiceProvider::create<ControllerService>(dataModel.get());
		service->setHardwareDevice(NULL);

		view->bindWorkspace(shared_ptr<DataModel>());

		userInput.reset();

		leaveGameVerb.reset();
		fullscreenVerb.reset();
		studioVerb.reset();
		screenshotVerb.reset();
		shutdownClientVerb.reset();
	}
	else
	{
		userInput.reset();
	}
}

void RobloxView::stopJobs()
{
	if (renderJob)
	{
		renderJob->abortRender();

		boost::function<void()> callback = boost::bind(&FunctionMarshaller::ProcessMessages, marshaller);
		TaskScheduler::singleton().removeBlocking(renderJob, callback);
	}

	// removeBlocking returned, so the render job is done - but it may have
	// marshalled renderPerform before exiting. Drain it now, while the view it
	// points at is alive.
	marshaller->ProcessMessages();

	renderJob.reset();
}

void RobloxView::defineConcurrencyRules()
{
	{
		// Keep view updates and rendering serial.
		shared_ptr<Tasks::Coordinator> exclusive(new Tasks::ExclusiveSequence());
		renderJob->addCoordinator(exclusive);
	}

	if (CRenderSettingsItem::singleton().isSynchronizedWithPhysics)
	{
		// Force rendering and physics to happen in lock-step.
		sequence.reset(new Tasks::Sequence());
		renderJob->addCoordinator(sequence);
		if (shared_ptr<DataModel> dataModel = game->getDataModel())
			dataModel->create<RunService>()->getPhysicsJob()->addCoordinator(sequence);
	}
}

void RobloxView::bindToWorkspace(bool buildGui)
{
	shared_ptr<DataModel> dataModel = game->getDataModel();
	if (!dataModel)
		return;

	DataModel::LegacyLock lock(dataModel, DataModelJob::Write);

	view->bindWorkspace(dataModel);
	if (buildGui)
		view->buildGui();

	if (userInput)
	{
		ControllerService* service = ServiceProvider::create<ControllerService>(dataModel.get());
		service->setHardwareDevice(userInput.get());
	}
}

void RobloxView::setBounds(unsigned width, unsigned height)
{
	this->width = width;
	this->height = height;
	if (view)
		view->onResize(width, height);
}

bool RobloxView::requestShutdownClient()
{
	if (shared_ptr<DataModel> dataModel = game->getDataModel())
	{
		if (FLog::PlayerShutdownLuaTimeoutSeconds > 0)
			if (ScriptContext* scriptContext = dataModel->find<ScriptContext>())
				scriptContext->setTimeout(FLog::PlayerShutdownLuaTimeoutSeconds);

		return dataModel->requestShutdown() == DataModel::CLOSE_REQUEST_HANDLED;
	}
	return true;
}

void RobloxView::handleFocus(bool focus)
{
	if (userInput)
		userInput->PostUserInputMessage(InputObject::TYPE_FOCUS, focus ? InputObject::INPUT_STATE_BEGIN : InputObject::INPUT_STATE_END, 0, 0);
}

void RobloxView::handleMouseInside(bool inside)
{
	if (!userInput)
		return;

	if (inside)
		userInput->onMouseInside();
	else
		userInput->onMouseLeave();
}

static InputObject::UserInputType eventToInputType(RobloxView::EventType event)
{
	switch (event)
	{
	case RobloxView::MOUSE_MOVE: return InputObject::TYPE_MOUSEMOVEMENT;
	case RobloxView::MOUSE_LEFT_BUTTON_DOWN:
	case RobloxView::MOUSE_LEFT_BUTTON_UP: return InputObject::TYPE_MOUSEBUTTON1;
	case RobloxView::MOUSE_RIGHT_BUTTON_DOWN:
	case RobloxView::MOUSE_RIGHT_BUTTON_UP: return InputObject::TYPE_MOUSEBUTTON2;
	case RobloxView::MOUSE_MIDDLE_BUTTON_DOWN:
	case RobloxView::MOUSE_MIDDLE_BUTTON_UP: return InputObject::TYPE_MOUSEBUTTON3;
	case RobloxView::KEY_DOWN:
	case RobloxView::KEY_UP: return InputObject::TYPE_KEYBOARD;
	}
	return InputObject::TYPE_NONE;
}

static InputObject::UserInputState eventToInputState(RobloxView::EventType event)
{
	switch (event)
	{
	case RobloxView::MOUSE_MOVE: return InputObject::INPUT_STATE_CHANGE;
	case RobloxView::MOUSE_LEFT_BUTTON_DOWN:
	case RobloxView::MOUSE_RIGHT_BUTTON_DOWN:
	case RobloxView::MOUSE_MIDDLE_BUTTON_DOWN:
	case RobloxView::KEY_DOWN: return InputObject::INPUT_STATE_BEGIN;
	case RobloxView::MOUSE_LEFT_BUTTON_UP:
	case RobloxView::MOUSE_RIGHT_BUTTON_UP:
	case RobloxView::MOUSE_MIDDLE_BUTTON_UP:
	case RobloxView::KEY_UP: return InputObject::INPUT_STATE_END;
	}
	return InputObject::INPUT_STATE_NONE;
}

void RobloxView::handleMouse(EventType event, int x, int y, ModCode modifiers)
{
	if (userInput)
		userInput->PostUserInputMessage(eventToInputType(event), eventToInputState(event), modifiers, MAKEXYLPARAM((unsigned) x, (unsigned) y));
}

void RobloxView::handleKey(EventType event, KeyCode keyCode, ModCode modifiers, const std::string& text)
{
	if (userInput)
		userInput->PostUserInputMessage(eventToInputType(event), eventToInputState(event), keyCode, modifiers, text);
}

void RobloxView::handleText(const std::string& text)
{
	if (userInput && !text.empty())
		userInput->PostUserInputMessage(InputObject::TYPE_KEYBOARD, InputObject::INPUT_STATE_BEGIN, RBX::SDLK_UNKNOWN, RBX::KMOD_NONE, text);
}

void RobloxView::handleScrollWheel(float delta, int x, int y)
{
	if (userInput)
		userInput->PostUserInputMessage(InputObject::TYPE_MOUSEWHEEL, InputObject::INPUT_STATE_CHANGE, (int) delta, MAKEXYLPARAM((unsigned) x, (unsigned) y));
}

void RobloxView::syncCursor(int x, int y)
{
	if (userInput)
		userInput->PostCursorPosition(x, y);
}

void RobloxView::getCursorPos(G3D::Vector2* pos)
{
	*pos = cursorPos;
}

void RobloxView::setCursorPos(G3D::Vector2 pos)
{
	cursorPos = pos;
}

bool RobloxView::isFullscreenMode() const
{
	return host && host->isFullScreen();
}

void RobloxView::leaveGame()
{
	if (host)
		marshaller->Submit(boost::bind(&RobloxViewHost::leaveGame, host));
}

void RobloxView::toggleFullScreen()
{
	if (host)
		marshaller->Submit(boost::bind(&RobloxViewHost::toggleFullScreen, host));
}
