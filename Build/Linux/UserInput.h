// Keyboard, mouse and gamepad input for the Linux player, adapted from the Mac
// player's UserInput.  Window events are queued from the main thread and
// replayed into UserInputService on the DataModel's early render step.
#pragma once

#include <string>
#include <vector>

#include <boost/thread/mutex.hpp>
#include <boost/unordered_map.hpp>

#include "../RobloxMac/UserInputUtil.h"

#include "util/UserInputBase.h"
#include "v8datamodel/InputObject.h"
#include "rbx/signal.h"

class SDLGameController;
class RobloxView;

namespace RBX
{
	class Game;
}

class UserInput : public RBX::UserInputBase
{
	struct BufferedEvent
	{
		RBX::InputObject::UserInputType userInputType;
		RBX::InputObject::UserInputState userInputState;
		unsigned int wParam;
		unsigned int lParam;
		std::string text;           // keyboard: the UTF-8 the key typed, in the active layout
	};

	rbx::signals::scoped_connection renderStepConnection;

	bool isMouseCaptured;
	bool leftMouseButtonDown;
	bool rightMouseDown;
	bool autoMouseMove;
	bool isMouseInside;

	G3D::Vector2 wrapMousePosition; // relative to the window center
	G3D::Vector2 posToWrapTo;
	G3D::Vector2 accumulatedFractionalMouseDelta;

	int externallyForcedKeyDown;

	RobloxView* wnd;

	boost::mutex bufferMutex;
	std::vector<BufferedEvent> bufferedEvents;

	boost::unordered_map<RBX::InputObject::UserInputType, shared_ptr<RBX::InputObject> > inputObjectMap;
	boost::unordered_map<RBX::KeyCode, shared_ptr<RBX::InputObject> > keyboardInputObjects;

	shared_ptr<SDLGameController> sdlGameController;

	void sendEvent(const shared_ptr<RBX::InputObject>& event);
	void sendMouseEvent(RBX::InputObject::UserInputType mouseEventType, RBX::InputObject::UserInputState mouseEventState, const RBX::Vector3& position, const RBX::Vector3& delta);

	G3D::Vector2int16 getWindowSize() const;
	G3D::Rect2D getWindowRect() const;
	bool isFullScreenMode() const;
	bool movementKeysDown();
	bool keyDownInternal(RBX::KeyCode code) const;

	void doWrapMouse(const G3D::Vector2& delta, G3D::Vector2& wrapMouseDelta);

	void processInput();
	// Call only within a DataModel lock.
	void ProcessUserInputMessage(RBX::InputObject::UserInputType eventType, RBX::InputObject::UserInputState eventState, unsigned int wParam, unsigned int lParam, const std::string& text);

public:
	shared_ptr<RBX::Game> game;

	UserInput(RobloxView* wnd, shared_ptr<RBX::Game> game);
	~UserInput();

	void PostUserInputMessage(RBX::InputObject::UserInputType eventType, RBX::InputObject::UserInputState eventState, unsigned int wParam, unsigned int lParam, const std::string& text = std::string());

	// Puts the engine's cursor where the pointer really is. For a pointer that
	// is not captured (a browser without pointer lock), where motion deltas
	// alone would let the two drift apart. Queued behind earlier input.
	void PostCursorPosition(int x, int y);

	void onMouseInside();
	void onMouseLeave();

	// UserInputBase
	/*implement*/ RBX::Vector2 getCursorPosition();
	/*implement*/ bool keyDown(RBX::KeyCode code) const;
	/*implement*/ void setKeyState(RBX::KeyCode code, RBX::ModCode modCode, char modifiedKey, bool isDown);
	/*implement*/ void centerCursor();
};
