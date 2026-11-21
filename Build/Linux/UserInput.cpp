#include "UserInput.h"

#include "RobloxView.h"
#include "SDLGameController.h"

#include "util/Rect.h"
#include "util/standardout.h"
#include "v8datamodel/Camera.h"
#include "v8datamodel/DataModel.h"
#include "v8datamodel/Game.h"
#include "v8datamodel/GameBasicSettings.h"
#include "v8datamodel/UserInputService.h"
#include "v8datamodel/Workspace.h"

DYNAMIC_FASTFLAGVARIABLE(MouseDeltaWhenNotMouseLocked, false)
FASTFLAG(UserAllCamerasInLua)

UserInput::UserInput(RobloxView* wnd, shared_ptr<RBX::Game> newGame)
	: isMouseCaptured(false)
	, leftMouseButtonDown(false)
	, rightMouseDown(false)
	, autoMouseMove(false)
	, isMouseInside(false)
	, posToWrapTo(0, 0)
	, externallyForcedKeyDown(0)
	, wnd(wnd)
	, game(newGame)
{
	if (shared_ptr<RBX::DataModel> dataModel = game->getDataModel())
	{
		sdlGameController.reset(new SDLGameController(dataModel));

		if (RBX::RunService* runService = RBX::ServiceProvider::create<RBX::RunService>(dataModel.get()))
			renderStepConnection = runService->earlyRenderSignal.connect(boost::bind(&UserInput::processInput, this));
	}
}

UserInput::~UserInput()
{
	renderStepConnection.disconnect();
	sdlGameController.reset();
}

void UserInput::PostUserInputMessage(RBX::InputObject::UserInputType eventType, RBX::InputObject::UserInputState eventState, unsigned int wParam, unsigned int lParam, const std::string& text)
{
	BufferedEvent event;
	event.userInputType = eventType;
	event.userInputState = eventState;
	event.wParam = wParam;
	event.lParam = lParam;
	event.text = text;

	boost::mutex::scoped_lock lock(bufferMutex);
	bufferedEvents.push_back(event);
}

void UserInput::PostCursorPosition(int x, int y)
{
	// TYPE_NONE / INPUT_STATE_NONE never comes from a real device.
	PostUserInputMessage(RBX::InputObject::TYPE_NONE, RBX::InputObject::INPUT_STATE_NONE, 0, MAKEXYLPARAM((unsigned) x, (unsigned) y));
}

void UserInput::processInput()
{
	std::vector<BufferedEvent> events;
	{
		boost::mutex::scoped_lock lock(bufferMutex);
		bufferedEvents.swap(events);
	}

	for (std::vector<BufferedEvent>::iterator iter = events.begin(); iter != events.end(); ++iter)
		ProcessUserInputMessage(iter->userInputType, iter->userInputState, iter->wParam, iter->lParam, iter->text);
}

void UserInput::ProcessUserInputMessage(RBX::InputObject::UserInputType eventType, RBX::InputObject::UserInputState eventState, unsigned int wParam, unsigned int lParam, const std::string& text)
{
	shared_ptr<RBX::DataModel> dataModel = game->getDataModel();
	if (!dataModel)
		return;

	bool cursorMoved = false;
	RBX::Vector2 wrapMouseDelta;
	G3D::Vector2 mouseDelta;

	switch (eventType)
	{
	case RBX::InputObject::TYPE_KEYBOARD:
	{
		const RBX::KeyCode keyCode = (RBX::KeyCode) wParam;
		const RBX::ModCode modCode = (RBX::ModCode) lParam;
		const bool isDown = (eventState == RBX::InputObject::INPUT_STATE_BEGIN);
		// What the layout typed wins over the US character of the key, as
		// on Windows (ToUnicodeEx): TextBox inserts the text, and anything that
		// only looks at the character still sees a non-zero one.
		const char key = text.empty() ? RBX::UserInputService::getModifiedKey(keyCode, modCode) : text[0];

		// Text with no key behind it - an emoji picker, an input method, a
		// paste into the web page - is one event, like Windows' WM_CHAR.
		if (keyCode == RBX::SDLK_UNKNOWN)
		{
			if (!text.empty())
				sendEvent(RBX::Creatable<RBX::Instance>::create<RBX::InputObject>(eventType, RBX::InputObject::INPUT_STATE_BEGIN, keyCode, modCode, key, text, dataModel.get()));
			break;
		}

		if (RBX::UserInputService* userInputService = RBX::ServiceProvider::find<RBX::UserInputService>(dataModel.get()))
			userInputService->setKeyState(keyCode, modCode, key, isDown);

		if (eventState == RBX::InputObject::INPUT_STATE_END && keyboardInputObjects.find(keyCode) != keyboardInputObjects.end())
		{
			shared_ptr<RBX::InputObject> keyInput = keyboardInputObjects[keyCode];
			keyInput->setInputState(RBX::InputObject::INPUT_STATE_END);
			keyInput->mod = modCode;
			keyInput->modifiedKey = key;

			sendEvent(keyInput);
			keyboardInputObjects.erase(keyCode);
		}
		else
		{
			shared_ptr<RBX::InputObject> keyInput = RBX::Creatable<RBX::Instance>::create<RBX::InputObject>(eventType, eventState, keyCode, modCode, key, text, dataModel.get());
			keyboardInputObjects[keyCode] = keyInput;
			sendEvent(keyInput);
		}
		break;
	}
	case RBX::InputObject::TYPE_MOUSEBUTTON1:
	case RBX::InputObject::TYPE_MOUSEBUTTON2:
	case RBX::InputObject::TYPE_MOUSEBUTTON3:
	{
		sendMouseEvent(eventType, eventState, RBX::Vector3(getCursorPosition(), 0), RBX::Vector3(wrapMouseDelta.x, wrapMouseDelta.y, 0));

		const bool down = eventState == RBX::InputObject::INPUT_STATE_BEGIN;
		if (eventType == RBX::InputObject::TYPE_MOUSEBUTTON1)
		{
			leftMouseButtonDown = down;
			isMouseCaptured = down;
		}
		else if (eventType == RBX::InputObject::TYPE_MOUSEBUTTON2)
		{
			rightMouseDown = down;
		}
		break;
	}
	case RBX::InputObject::TYPE_MOUSEMOVEMENT:
	{
		mouseDelta = G3D::Vector2((float)GET_X_LPARAM(lParam), (float)GET_Y_LPARAM(lParam));

		autoMouseMove = false;
		cursorMoved = true;

		accumulatedFractionalMouseDelta += mouseDelta * RBX::GameBasicSettings::singleton().getMouseSensitivity();
		mouseDelta.x = (int) accumulatedFractionalMouseDelta.x;
		mouseDelta.y = (int) accumulatedFractionalMouseDelta.y;
		accumulatedFractionalMouseDelta -= mouseDelta;

		doWrapMouse(mouseDelta, wrapMouseDelta);
		break;
	}
	case RBX::InputObject::TYPE_MOUSEWHEEL:
	{
		int zDelta = (int) wParam;
		G3D::Vector2 pos = getCursorPosition();
		sendMouseEvent(RBX::InputObject::TYPE_MOUSEWHEEL, RBX::InputObject::INPUT_STATE_CHANGE,
			RBX::Vector3(pos.x, pos.y, zDelta), RBX::Vector3(wrapMouseDelta.x, wrapMouseDelta.y, 0));
		break;
	}
	case RBX::InputObject::TYPE_NONE:
	{
		if (eventState != RBX::InputObject::INPUT_STATE_NONE)
			break;

		const G3D::Vector2 pos((float)GET_X_LPARAM(lParam), (float)GET_Y_LPARAM(lParam));
		wrapMousePosition = pos - getWindowRect().center();
		wnd->setCursorPos(pos);
		cursorMoved = true;
		break;
	}
	case RBX::InputObject::TYPE_FOCUS:
	{
		shared_ptr<RBX::InputObject> event = RBX::Creatable<RBX::Instance>::create<RBX::InputObject>(RBX::InputObject::TYPE_FOCUS, eventState, dataModel.get());
		sendEvent(event);
		break;
	}
	default:
		break;
	}

	if (wrapMouseDelta != RBX::Vector2::zero())
	{
		if (RBX::Workspace* workspace = dataModel->getWorkspace())
		{
			if (!FFlag::UserAllCamerasInLua || !workspace->getCamera()->hasClientPlayer())
				if (workspace->getCamera()->getCameraType() != RBX::Camera::CUSTOM_CAMERA)
					workspace->onWrapMouse(wrapMouseDelta);
		}

		sendMouseEvent(RBX::InputObject::TYPE_MOUSEDELTA, RBX::InputObject::INPUT_STATE_CHANGE, RBX::Vector3(getCursorPosition(), 0), RBX::Vector3(wrapMouseDelta.x, wrapMouseDelta.y, 0));
		sendMouseEvent(RBX::InputObject::TYPE_MOUSEMOVEMENT, RBX::InputObject::INPUT_STATE_CHANGE, RBX::Vector3(getCursorPosition(), 0), RBX::Vector3(wrapMouseDelta.x, wrapMouseDelta.y, 0));
	}
	else if (cursorMoved)
	{
		const G3D::Vector2 delta = DFFlag::MouseDeltaWhenNotMouseLocked ? mouseDelta : G3D::Vector2(wrapMouseDelta);
		sendMouseEvent(RBX::InputObject::TYPE_MOUSEMOVEMENT, RBX::InputObject::INPUT_STATE_CHANGE, RBX::Vector3(getCursorPosition(), 0), RBX::Vector3(delta.x, delta.y, 0));
	}
}

void UserInput::sendMouseEvent(RBX::InputObject::UserInputType mouseEventType, RBX::InputObject::UserInputState mouseEventState, const RBX::Vector3& position, const RBX::Vector3& delta)
{
	shared_ptr<RBX::InputObject> mouseEventObject;
	if (inputObjectMap.find(mouseEventType) == inputObjectMap.end())
	{
		mouseEventObject = RBX::Creatable<RBX::Instance>::create<RBX::InputObject>(mouseEventType, mouseEventState, position, delta, game->getDataModel().get());
		inputObjectMap[mouseEventType] = mouseEventObject;
	}
	else
	{
		mouseEventObject = inputObjectMap[mouseEventType];
		mouseEventObject->setInputState(mouseEventState);
		mouseEventObject->setPosition(position);
		mouseEventObject->setDelta(delta);
	}
	sendEvent(mouseEventObject);
}

void UserInput::sendEvent(const shared_ptr<RBX::InputObject>& event)
{
	if (shared_ptr<RBX::DataModel> dataModel = game->getDataModel())
		if (RBX::UserInputService* inputService = RBX::ServiceProvider::find<RBX::UserInputService>(dataModel.get()))
			inputService->fireInputEvent(event, NULL);
}

bool UserInput::isFullScreenMode() const
{
	return wnd->isFullscreenMode();
}

G3D::Rect2D UserInput::getWindowRect() const
{
	return G3D::Rect2D(G3D::Vector2(wnd->width, wnd->height));
}

G3D::Vector2int16 UserInput::getWindowSize() const
{
	G3D::Rect2D windowRect = getWindowRect();
	return G3D::Vector2int16((G3D::int16) windowRect.width(), (G3D::int16) windowRect.height());
}

bool UserInput::movementKeysDown()
{
	return keyDownInternal(RBX::SDLK_w) || keyDownInternal(RBX::SDLK_s) || keyDownInternal(RBX::SDLK_a) || keyDownInternal(RBX::SDLK_d)
		|| keyDownInternal(RBX::SDLK_UP) || keyDownInternal(RBX::SDLK_DOWN) || keyDownInternal(RBX::SDLK_LEFT) || keyDownInternal(RBX::SDLK_RIGHT);
}

void UserInput::onMouseInside()
{
	G3D::Vector2 pos;
	wnd->getCursorPos(&pos);
	wrapMousePosition = pos - getWindowRect().center();
	isMouseInside = true;
}

void UserInput::onMouseLeave()
{
	isMouseInside = false;
}

void UserInput::centerCursor()
{
	wrapMousePosition = RBX::Vector2::zero();
	wnd->setCursorPos(getWindowRect().center());
}

G3D::Vector2 UserInput::getCursorPosition()
{
	G3D::Vector2 pt;
	wnd->getCursorPos(&pt);
	return pt;
}

bool UserInput::keyDownInternal(RBX::KeyCode code) const
{
	if (externallyForcedKeyDown && code == externallyForcedKeyDown)
		return true;

	boost::unordered_map<RBX::KeyCode, shared_ptr<RBX::InputObject> >::const_iterator iter = keyboardInputObjects.find(code);
	if (iter != keyboardInputObjects.end() && iter->second)
		return iter->second->getUserInputState() != RBX::InputObject::INPUT_STATE_END;

	return false;
}

bool UserInput::keyDown(RBX::KeyCode code) const
{
	return keyDownInternal(code);
}

void UserInput::setKeyState(RBX::KeyCode code, RBX::ModCode modCode, char modifiedKey, bool isDown)
{
	externallyForcedKeyDown = isDown ? code : 0;
}

void UserInput::doWrapMouse(const G3D::Vector2& delta, G3D::Vector2& wrapMouseDelta)
{
	RBX::UserInputService* userInputService = RBX::ServiceProvider::find<RBX::UserInputService>(game->getDataModel().get());
	if (!userInputService)
		return;

	switch (userInputService->getMouseWrapMode())
	{
	case RBX::UserInputService::WRAP_NONEANDCENTER:
		centerCursor();
		// fall through
	case RBX::UserInputService::WRAP_NONE:
	case RBX::UserInputService::WRAP_CENTER:
		UserInputUtil::wrapMouseCenter(delta, wrapMouseDelta, wrapMousePosition);
		break;
	case RBX::UserInputService::WRAP_HYBRID:
		UserInputUtil::wrapMousePos(delta, wrapMouseDelta, wrapMousePosition, getWindowSize(), posToWrapTo, autoMouseMove);
		break;
	case RBX::UserInputService::WRAP_AUTO:
		// Keep the mouse in the window while moving or dragging; otherwise let
		// it go, so it can leave the window.
		if (movementKeysDown() || isMouseCaptured)
			UserInputUtil::wrapMouseBorderLock(delta, wrapMouseDelta, wrapMousePosition, getWindowSize());
		else if (isFullScreenMode())
			UserInputUtil::wrapFullScreen(delta, wrapMouseDelta, wrapMousePosition, getWindowSize());
		else
			UserInputUtil::wrapMouseNone(delta, wrapMouseDelta, wrapMousePosition);
		break;
	}

	wnd->setCursorPos(getWindowRect().center() + wrapMousePosition);
}
