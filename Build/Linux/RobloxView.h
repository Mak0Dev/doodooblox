// The game view: binds a Game's DataModel to a GL window, schedules the render
// and input jobs, and routes window input into the engine.  Adapted from the
// Mac player's RobloxView, whose C++ half this mirrors.
#pragma once

#include <string>

#include <boost/scoped_ptr.hpp>
#include <boost/shared_ptr.hpp>

#include "g3d/Vector2.h"
#include "rbx/signal.h"
#include "util/KeyCode.h"
#include "v8datamodel/Game.h"
#include "v8datamodel/InputObject.h"
#include "v8tree/Verb.h"

struct SDL_Window;

namespace RBX
{
	class DataModel;
	class ViewBase;
	class FunctionMarshaller;
	namespace Tasks
	{
		class Sequence;
	}
}

class UserInput;

// Hooks the view needs from the application that owns the window.
class RobloxViewHost
{
public:
	virtual ~RobloxViewHost() {}
	virtual void leaveGame() = 0;
	virtual void toggleFullScreen() = 0;
	virtual bool isFullScreen() const = 0;
};

class RobloxView
{
public:
	enum EventType
	{
		MOUSE_RIGHT_BUTTON_DOWN,
		MOUSE_RIGHT_BUTTON_UP,
		MOUSE_LEFT_BUTTON_DOWN,
		MOUSE_LEFT_BUTTON_UP,
		MOUSE_MIDDLE_BUTTON_DOWN,
		MOUSE_MIDDLE_BUTTON_UP,
		MOUSE_MOVE,
		KEY_DOWN,
		KEY_UP,
	};

	// A view that owns its game shuts it down when it closes. The console
	// mode's view does not: the console keeps the app shell and the game it
	// switches between (ConsoleShell).
	RobloxView(SDL_Window* window, RobloxViewHost* host, boost::shared_ptr<RBX::Game> game, unsigned width, unsigned height,
		bool ownsGame = true, bool buildGui = true);
	~RobloxView();

	// Binds the view to another game, as the Xbox app switched between its
	// shell and a game (XboxPlatform::switchView). The game left behind keeps
	// running. buildGui: the game's first time in a view - its core GUI and
	// scripts are made then, once per DataModel.
	void setGame(boost::shared_ptr<RBX::Game> game, bool buildGui);

	// Input, from the main thread.
	void handleFocus(bool focus);
	void handleMouseInside(bool inside);
	void handleMouse(EventType event, int x, int y, RBX::ModCode modifiers);
	// text: the UTF-8 the key typed in the active layout, if any.
	void handleKey(EventType event, RBX::KeyCode keyCode, RBX::ModCode modifiers, const std::string& text = std::string());
	// Typed text with no key behind it (emoji picker, input method, paste).
	void handleText(const std::string& text);
	void handleScrollWheel(float delta, int x, int y);
	// Absolute pointer position, for input that is not captured.
	void syncCursor(int x, int y);

	// The engine draws its own cursor; this is where it is, in window pixels.
	void getCursorPos(G3D::Vector2* pos);
	void setCursorPos(G3D::Vector2 pos);

	bool isFullscreenMode() const;

	// Asks the game to close; false while a script is still handling it.
	bool requestShutdownClient();

	// Game verbs, from any thread.
	void leaveGame();
	void toggleFullScreen();

	void setBounds(unsigned width, unsigned height);
	unsigned width;
	unsigned height;

	boost::shared_ptr<RBX::Game> getGame() { return game; }
	boost::shared_ptr<RBX::DataModel> getDataModel() { return game ? game->getDataModel() : boost::shared_ptr<RBX::DataModel>(); }

private:
	class RenderJob;

	SDL_Window* window;
	RobloxViewHost* host;
	boost::scoped_ptr<RBX::ViewBase> view;
	boost::shared_ptr<RBX::Game> game;
	bool ownsGame;
	boost::scoped_ptr<UserInput> userInput;
	boost::scoped_ptr<RBX::Verb> leaveGameVerb;
	boost::scoped_ptr<RBX::Verb> fullscreenVerb;
	boost::scoped_ptr<RBX::Verb> studioVerb;
	boost::scoped_ptr<RBX::Verb> screenshotVerb;
	boost::scoped_ptr<RBX::Verb> shutdownClientVerb;

	RBX::FunctionMarshaller* marshaller;

	boost::shared_ptr<RBX::Tasks::Sequence> sequence;
	boost::shared_ptr<RenderJob> renderJob;

	G3D::Vector2 cursorPos;

	void bind(bool buildGui);
	void unbind(bool closing);
	void bindToWorkspace(bool buildGui);
	void defineConcurrencyRules();
	void stopJobs();
};
