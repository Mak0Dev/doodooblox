// Better16 player for desktop Linux: an SDL2 window with an OpenGL ES context,
// driving the same engine the Windows, Mac and Android players do.
//
//   RobloxPlayerLinux --play -a <authUrl> -t <ticket> -j <placeLauncherUrl>
//   RobloxPlayerLinux 'better16://placeId=1818&key=<ticket>[&jobId=<id>]'
//   RobloxPlayerLinux --file place.rbxl
//   RobloxPlayerLinux --console
//
// The first form is what the Windows bootstrapper hands RobloxPlayerBeta.exe;
// the second is the site's play link itself, for registering the binary as the
// URI scheme handler. --console is the Xbox One app instead of one game: the
// web player's xplay.<domain> (ConsoleShell).
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <deque>
#include <fstream>
#include <sstream>
#include <string>
#include <exception>

#ifndef __EMSCRIPTEN__
#include <execinfo.h>
#endif
#include <unistd.h>

#include <boost/filesystem.hpp>
#include <boost/scoped_ptr.hpp>
#include <boost/thread.hpp>

#include <SDL.h>

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#include <emscripten/html5.h>
#include <emscripten/threading.h>
#endif

#include "ConsoleShell.h"
#include "LinuxApp.h"
#include "RobloxView.h"
#include "SDLGameController.h"

#include "util/standardout.h"
#include "v8datamodel/Game.h"
#include "v8datamodel/GameBasicSettings.h"

namespace
{

// An exception nobody catches reaches terminate before the stack unwinds, so
// the stack printed here is the throw site. Resolve with
// addr2line -Cfpe RobloxPlayerLinux <offset>.
void onTerminate()
{
	fprintf(stderr, "Unhandled exception");
	if (std::exception_ptr current = std::current_exception())
	{
		try { std::rethrow_exception(current); }
		catch (std::exception& e) { fprintf(stderr, ": %s", e.what()); }
		catch (...) {}
	}
	fprintf(stderr, "\n");

#ifndef __EMSCRIPTEN__
	void* frames[64];
	int count = backtrace(frames, 64);
	backtrace_symbols_fd(frames, count, 2);
#endif
	abort();
}

void printUsage(const char* exe)
{
	fprintf(stderr,
		"Usage:\n"
		"  %s [options] --play -a <authUrl> -t <ticket> -j <placeLauncherOrJoinUrl>\n"
		"  %s [options] <scheme>://placeId=<id>&key=<ticket>[&jobId=<id>]\n"
		"  %s [options] --file <place.rbxl>\n"
		"  %s [options] --console\n"
		"\n"
		"Options:\n"
		"  --base-url <url>     site base URL (default: BaseUrl in AppSettings.xml)\n"
		"  --content <dir>      content folder (default: content/ next to the binary)\n"
		"  --width <px>, --height <px>, --fullscreen\n"
		"  --hash <value>       client hash reported to game servers\n"
		"  --fps-cap <0-5>      frame rate cap: 60, 90, 120, 180, 240, unlimited\n"
		"  --hear-voice <0|1>   voice chat: 0 hears no one\n"
		"  --flog <Name=Value>  set a FastLog/FastFlag and print FastLog to stderr\n",
		exe, exe, exe, exe);
}

std::string executableFolder()
{
	char buffer[4096];
	ssize_t n = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
	if (n <= 0)
		return ".";
	buffer[n] = 0;
	return boost::filesystem::path(buffer).parent_path().string();
}

std::string readTag(const std::string& xml, const std::string& tag)
{
	std::string open = "<" + tag + ">", close = "</" + tag + ">";
	size_t begin = xml.find(open);
	if (begin == std::string::npos)
		return "";
	begin += open.size();
	size_t end = xml.find(close, begin);
	return end == std::string::npos ? "" : xml.substr(begin, end - begin);
}

// AppSettings.xml, as the Windows player and Studio read it:
// <Settings><ContentFolder>content</ContentFolder><BaseUrl>http://...</BaseUrl></Settings>
void readAppSettings(const std::string& folder, LaunchOptions& options)
{
	std::ifstream file((folder + "/AppSettings.xml").c_str());
	if (!file)
		return;

	std::stringstream data;
	data << file.rdbuf();

	if (options.baseUrl.empty())
		options.baseUrl = readTag(data.str(), "BaseUrl");

	if (options.contentFolder.empty())
	{
		std::string content = readTag(data.str(), "ContentFolder");
		if (!content.empty())
			options.contentFolder = content[0] == '/' ? content : folder + "/" + content;
	}
}

std::string urlDecode(const std::string& s)
{
	std::string out;
	for (size_t i = 0; i < s.size(); ++i)
	{
		if (s[i] == '%' && i + 2 < s.size())
		{
			out += (char) strtol(s.substr(i + 1, 2).c_str(), NULL, 16);
			i += 2;
		}
		else
			out += s[i] == '+' ? ' ' : s[i];
	}
	return out;
}

std::string lower(std::string s)
{
	for (size_t i = 0; i < s.size(); ++i)
		s[i] = tolower(s[i]);
	return s;
}

// The site's link: <scheme>://placeId=1818&key=<ticket>[&jobId=<id>].  Turned
// into the same PlaceLauncher + Negotiate pair the Windows bootstrapper builds
// (Installer/BootstrapperClient/BootstrapperClient.cpp).
bool parseProtocolLink(const std::string& link, LaunchOptions& options)
{
	size_t scheme = link.find("://");
	if (scheme == std::string::npos)
		return false;

	std::string body = link.substr(scheme + 3);
	while (!body.empty() && body[body.size() - 1] == '/')
		body.erase(body.size() - 1);

	std::string placeId, key, jobId;
	std::stringstream parts(body);
	std::string part;
	while (std::getline(parts, part, '&'))
	{
		size_t eq = part.find('=');
		if (eq == std::string::npos)
			continue;
		std::string name = lower(part.substr(0, eq));
		std::string value = urlDecode(part.substr(eq + 1));
		if (name == "placeid") placeId = value;
		else if (name == "key") key = value;
		else if (name == "jobid") jobId = value;
	}

	if (placeId.empty())
		return false;

	std::string base = options.baseUrl;
	if (!base.empty() && base[base.size() - 1] == '/')
		base.erase(base.size() - 1);

	options.script = base + "/Game/PlaceLauncher.ashx?request=" + (jobId.empty() ? "RequestGame" : "RequestGameJob") + "&placeId=" + placeId;
	if (!jobId.empty())
		options.script += "&jobId=" + jobId;

	options.authUrl = base + "/Login/Negotiate.ashx";
	options.authTicket = key;
	return true;
}

// A key by where it is on a US keyboard. A layout without Latin letters
// (Russian, Greek...) gives SDL keycodes that are the letters' code points,
// which RBX::KeyCode has no names for; the game still wants its W, A, S, D.
RBX::KeyCode usKeyForScancode(SDL_Scancode scancode)
{
	if (scancode >= SDL_SCANCODE_A && scancode <= SDL_SCANCODE_Z)
		return (RBX::KeyCode) ('a' + (scancode - SDL_SCANCODE_A));
	if (scancode >= SDL_SCANCODE_1 && scancode <= SDL_SCANCODE_9)
		return (RBX::KeyCode) ('1' + (scancode - SDL_SCANCODE_1));

	switch (scancode)
	{
	case SDL_SCANCODE_0: return (RBX::KeyCode) '0';
	case SDL_SCANCODE_SPACE: return (RBX::KeyCode) ' ';
	case SDL_SCANCODE_MINUS: return (RBX::KeyCode) '-';
	case SDL_SCANCODE_EQUALS: return (RBX::KeyCode) '=';
	case SDL_SCANCODE_LEFTBRACKET: return (RBX::KeyCode) '[';
	case SDL_SCANCODE_RIGHTBRACKET: return (RBX::KeyCode) ']';
	case SDL_SCANCODE_BACKSLASH: return (RBX::KeyCode) '\\';
	case SDL_SCANCODE_SEMICOLON: return (RBX::KeyCode) ';';
	case SDL_SCANCODE_APOSTROPHE: return (RBX::KeyCode) '\'';
	case SDL_SCANCODE_GRAVE: return (RBX::KeyCode) '`';
	case SDL_SCANCODE_COMMA: return (RBX::KeyCode) ',';
	case SDL_SCANCODE_PERIOD: return (RBX::KeyCode) '.';
	case SDL_SCANCODE_SLASH: return (RBX::KeyCode) '/';
	default: return RBX::SDLK_UNKNOWN;
	}
}

// SDL2 keycodes -> RBX::KeyCode, which kept SDL 1.2's numbering.  ASCII is the
// same in both; everything above it moved.
RBX::KeyCode toRbxKey(const SDL_Keysym& keysym)
{
	const SDL_Keycode key = keysym.sym;
	if (key > 0 && key < 128)
		return (RBX::KeyCode) key;
	if (key == 0 || (key & SDLK_SCANCODE_MASK) == 0)
		return usKeyForScancode(keysym.scancode);

	switch (key)
	{
	case SDLK_KP_0: return RBX::SDLK_KP0;
	case SDLK_KP_1: return RBX::SDLK_KP1;
	case SDLK_KP_2: return RBX::SDLK_KP2;
	case SDLK_KP_3: return RBX::SDLK_KP3;
	case SDLK_KP_4: return RBX::SDLK_KP4;
	case SDLK_KP_5: return RBX::SDLK_KP5;
	case SDLK_KP_6: return RBX::SDLK_KP6;
	case SDLK_KP_7: return RBX::SDLK_KP7;
	case SDLK_KP_8: return RBX::SDLK_KP8;
	case SDLK_KP_9: return RBX::SDLK_KP9;
	case SDLK_KP_PERIOD: return RBX::SDLK_KP_PERIOD;
	case SDLK_KP_DIVIDE: return RBX::SDLK_KP_DIVIDE;
	case SDLK_KP_MULTIPLY: return RBX::SDLK_KP_MULTIPLY;
	case SDLK_KP_MINUS: return RBX::SDLK_KP_MINUS;
	case SDLK_KP_PLUS: return RBX::SDLK_KP_PLUS;
	case SDLK_KP_ENTER: return RBX::SDLK_KP_ENTER;
	case SDLK_KP_EQUALS: return RBX::SDLK_KP_EQUALS;
	case SDLK_UP: return RBX::SDLK_UP;
	case SDLK_DOWN: return RBX::SDLK_DOWN;
	case SDLK_RIGHT: return RBX::SDLK_RIGHT;
	case SDLK_LEFT: return RBX::SDLK_LEFT;
	case SDLK_INSERT: return RBX::SDLK_INSERT;
	case SDLK_HOME: return RBX::SDLK_HOME;
	case SDLK_END: return RBX::SDLK_END;
	case SDLK_PAGEUP: return RBX::SDLK_PAGEUP;
	case SDLK_PAGEDOWN: return RBX::SDLK_PAGEDOWN;
	case SDLK_F1: return RBX::SDLK_F1;
	case SDLK_F2: return RBX::SDLK_F2;
	case SDLK_F3: return RBX::SDLK_F3;
	case SDLK_F4: return RBX::SDLK_F4;
	case SDLK_F5: return RBX::SDLK_F5;
	case SDLK_F6: return RBX::SDLK_F6;
	case SDLK_F7: return RBX::SDLK_F7;
	case SDLK_F8: return RBX::SDLK_F8;
	case SDLK_F9: return RBX::SDLK_F9;
	case SDLK_F10: return RBX::SDLK_F10;
	case SDLK_F11: return RBX::SDLK_F11;
	case SDLK_F12: return RBX::SDLK_F12;
	case SDLK_F13: return RBX::SDLK_F13;
	case SDLK_F14: return RBX::SDLK_F14;
	case SDLK_F15: return RBX::SDLK_F15;
	case SDLK_NUMLOCKCLEAR: return RBX::SDLK_NUMLOCK;
	case SDLK_CAPSLOCK: return RBX::SDLK_CAPSLOCK;
	case SDLK_SCROLLLOCK: return RBX::SDLK_SCROLLOCK;
	case SDLK_RSHIFT: return RBX::SDLK_RSHIFT;
	case SDLK_LSHIFT: return RBX::SDLK_LSHIFT;
	case SDLK_RCTRL: return RBX::SDLK_RCTRL;
	case SDLK_LCTRL: return RBX::SDLK_LCTRL;
	case SDLK_RALT: return RBX::SDLK_RALT;
	case SDLK_LALT: return RBX::SDLK_LALT;
	case SDLK_LGUI: return RBX::SDLK_LSUPER;
	case SDLK_RGUI: return RBX::SDLK_RSUPER;
	case SDLK_MODE: return RBX::SDLK_MODE;
	case SDLK_HELP: return RBX::SDLK_HELP;
	case SDLK_PRINTSCREEN: return RBX::SDLK_PRINT;
	case SDLK_SYSREQ: return RBX::SDLK_SYSREQ;
	case SDLK_PAUSE: return RBX::SDLK_PAUSE;
	case SDLK_MENU: return RBX::SDLK_MENU;
	case SDLK_POWER: return RBX::SDLK_POWER;
	case SDLK_UNDO: return RBX::SDLK_UNDO;
	default: return RBX::SDLK_UNKNOWN;
	}
}

// SDL2's KMOD_* bits are SDL 1.2's, which RBX::ModCode kept.
RBX::ModCode toRbxMod(Uint16 mod)
{
	return (RBX::ModCode) (mod & (KMOD_LSHIFT | KMOD_RSHIFT | KMOD_LCTRL | KMOD_RCTRL | KMOD_LALT | KMOD_RALT |
		KMOD_LGUI | KMOD_RGUI | KMOD_NUM | KMOD_CAPS | KMOD_MODE));
}

// Controller events are pumped by the main loop with everything else and
// handed to SDLGameController on the DataModel's input step.
boost::mutex controllerEventMutex;
std::deque<SDL_Event> controllerEvents;

int pollControllerEvent(SDL_Event* event)
{
	boost::mutex::scoped_lock lock(controllerEventMutex);
	if (controllerEvents.empty())
		return 0;
	*event = controllerEvents.front();
	controllerEvents.pop_front();
	return 1;
}

// The console mode's shell answers gamepads only; there the keyboard is one.
// Arrows (or WASD) move the selection, Enter/Space is A, Esc/Backspace is B,
// Q/E the bumpers that switch tabs. Letters by where they are on a US
// keyboard in a layout without Latin ones, as toRbxKey reads them.
bool keyToGamepadButton(const SDL_Keysym& keysym, SDL_GameControllerButton& button)
{
	SDL_Keycode key = keysym.sym;
	if (key == 0 || (key >= 128 && (key & SDLK_SCANCODE_MASK) == 0))
		key = (SDL_Keycode) usKeyForScancode(keysym.scancode);

	switch (key)
	{
	case SDLK_UP: case SDLK_w: button = SDL_CONTROLLER_BUTTON_DPAD_UP; return true;
	case SDLK_DOWN: case SDLK_s: button = SDL_CONTROLLER_BUTTON_DPAD_DOWN; return true;
	case SDLK_LEFT: case SDLK_a: button = SDL_CONTROLLER_BUTTON_DPAD_LEFT; return true;
	case SDLK_RIGHT: case SDLK_d: button = SDL_CONTROLLER_BUTTON_DPAD_RIGHT; return true;
	case SDLK_RETURN: case SDLK_KP_ENTER: case SDLK_SPACE: button = SDL_CONTROLLER_BUTTON_A; return true;
	case SDLK_ESCAPE: case SDLK_BACKSPACE: button = SDL_CONTROLLER_BUTTON_B; return true;
	case SDLK_x: button = SDL_CONTROLLER_BUTTON_X; return true;
	case SDLK_y: button = SDL_CONTROLLER_BUTTON_Y; return true;
	case SDLK_q: case SDLK_PAGEUP: button = SDL_CONTROLLER_BUTTON_LEFTSHOULDER; return true;
	case SDLK_e: case SDLK_PAGEDOWN: button = SDL_CONTROLLER_BUTTON_RIGHTSHOULDER; return true;
	case SDLK_TAB: button = SDL_CONTROLLER_BUTTON_BACK; return true;
	case SDLK_m: button = SDL_CONTROLLER_BUTTON_START; return true;
	default: return false;
	}
}

#ifdef __EMSCRIPTEN__
bool pointerLocked()
{
	EmscriptenPointerlockChangeEvent status;
	return emscripten_get_pointerlock_status(&status) == EMSCRIPTEN_RESULT_SUCCESS && status.isActive;
}
#endif

class Player : public RobloxViewHost
{
	SDL_Window* window;
	LaunchOptions options;
	boost::scoped_ptr<RobloxView> view;
	boost::scoped_ptr<ConsoleShell> console;
	bool fullscreen;
	bool captured;
	bool focused;
	bool running;

	// A key that types something is held back until its SDL_TEXTINPUT, which
	// SDL sends right after it, so the two reach the engine as one event with
	// the layout's text - Cyrillic, accents, anything - the way Windows pairs
	// WM_KEYDOWN with ToUnicodeEx.
	struct PendingKey
	{
		bool valid;
		RBX::KeyCode key;
		RBX::ModCode mod;
		PendingKey() : valid(false), key(RBX::SDLK_UNKNOWN), mod(RBX::KMOD_NONE) {}
	} pendingKey;
	// The text after an auto-repeated key. TextBox repeats single characters
	// on its own clock, so only longer text (a repeated Cyrillic letter) is let
	// through.
	bool repeatingText;

public:
	Player(SDL_Window* window, const LaunchOptions& options)
		: window(window)
		, options(options)
		, fullscreen(options.fullscreen)
		, captured(false)
		, focused(true)
		, running(true)
		, repeatingText(false)
	{
		LinuxApp::setTeleportHandler(boost::bind(&Player::teleport, this, _1));
	}

	~Player()
	{
		LinuxApp::setTeleportHandler(boost::function<void(const LaunchOptions&)>());
	}

	void start(const LaunchOptions& launch)
	{
		int w = 0, h = 0;
		SDL_GL_GetDrawableSize(window, &w, &h);

		if (launch.console)
		{
			// One view for the shell and the games it starts; the console
			// owns the games and moves the view between them.
			console.reset(new ConsoleShell(launch, boost::bind(&Player::showGame, this, _1, _2)));
			boost::shared_ptr<RBX::Game> shell = console->createShell();
			view.reset(new RobloxView(window, this, shell, w, h, false, false));
			console->startShell();
			return;
		}

		boost::shared_ptr<RBX::Game> game = LinuxApp::createGame();
		view.reset(new RobloxView(window, this, game, w, h));

		if (!launch.placeFile.empty())
			boost::thread(boost::bind(&LinuxApp::openPlaceFile, game, launch.placeFile));
		else
			boost::thread(boost::bind(&LinuxApp::launchPlace, game, launch));
	}

	void stop()
	{
		setCaptured(false);
		view.reset();
		if (console)
		{
			console->shutdown();
			console.reset();
		}
	}

	bool isRunning() const { return running; }

	// The console's view moves between the shell and a game.
	void showGame(boost::shared_ptr<RBX::Game> game, bool buildGui)
	{
		if (view)
			view->setGame(game, buildGui);
	}

	// RobloxViewHost
	virtual void leaveGame()
	{
		// Leaving a game on the console is going back to the shell.
		if (console)
			console->leaveGame();
		else
			running = false;
	}

	virtual void toggleFullScreen()
	{
		fullscreen = !fullscreen;
		SDL_SetWindowFullscreen(window, fullscreen ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0);
		RBX::GameBasicSettings::singleton().setFullScreen(fullscreen);
	}

	virtual bool isFullScreen() const
	{
		return fullscreen;
	}

	void teleport(const LaunchOptions& launch)
	{
		if (console)
		{
			console->teleport(launch);
			return;
		}
		stop();
		start(launch);
	}

	// A key in the console's shell (or Esc on a game's loading screen): a
	// button of the keyboard's gamepad (SDLGameController::KeyboardJoystickId),
	// fed in with the controllers'. In a game the keyboard is a keyboard.
	bool handleConsoleKey(const SDL_Event& e)
	{
		if (!console)
			return false;

		SDL_GameControllerButton button;
		if (console->isShellShown())
		{
			if (!keyToGamepadButton(e.key.keysym, button))
				return false;
		}
		else if (console->isGameLoading() && (e.key.keysym.sym == SDLK_ESCAPE || e.key.keysym.sym == SDLK_BACKSPACE))
		{
			// "Cancel (B)" on the game's loading screen.
			button = SDL_CONTROLLER_BUTTON_B;
		}
		else
		{
			return false;
		}

		SDL_Event pad;
		SDL_zero(pad);
		pad.type = e.type == SDL_KEYDOWN ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
		pad.cbutton.which = SDLGameController::KeyboardJoystickId;
		pad.cbutton.button = button;
		pad.cbutton.state = e.type == SDL_KEYDOWN ? SDL_PRESSED : SDL_RELEASED;

		boost::mutex::scoped_lock lock(controllerEventMutex);
		controllerEvents.push_back(pad);
		return true;
	}

	// While captured the OS cursor is hidden and SDL reports relative motion;
	// the engine draws its own cursor where the view says it is. That is the Mac
	// player's model, and the one pointer lock gives a browser.
	void setCaptured(bool capture)
	{
		if (capture == captured)
			return;

		captured = capture;

#ifdef __EMSCRIPTEN__
		// The browser does the capturing: the page takes pointer lock on a
		// click (index.html), and SDL's relative mode, which asks for it
		// outside a user gesture, would fall back to warping - which a
		// browser cannot do, and which garbles every motion event after it.
		if (view)
			view->handleMouseInside(capture);
		return;
#endif

		SDL_SetRelativeMouseMode(capture ? SDL_TRUE : SDL_FALSE);

		if (!view)
			return;

		if (capture)
		{
			int x = 0, y = 0;
			SDL_GetMouseState(&x, &y);
			view->setCursorPos(G3D::Vector2(x, y) * drawableScale());
			view->handleMouseInside(true);
		}
		else
		{
			// Put the OS cursor where the engine's was, so it leaves the window
			// from the spot the player saw.
			G3D::Vector2 pos;
			view->getCursorPos(&pos);
			pos /= drawableScale();
			SDL_WarpMouseInWindow(window, (int) pos.x, (int) pos.y);
			view->handleMouseInside(false);
		}
	}

	float drawableScale() const
	{
		int w = 0, dw = 0, h = 0, dh = 0;
		SDL_GetWindowSize(window, &w, &h);
		SDL_GL_GetDrawableSize(window, &dw, &dh);
		return w > 0 ? (float) dw / w : 1.0f;
	}

	// Called once per loop: let the cursor go when the engine walks it out of a
	// windowed view.
	void updateCapture()
	{
#ifdef __EMSCRIPTEN__
		// A browser releases pointer lock itself (Esc), and there is no OS
		// cursor to hand back at the edge of the canvas.
		return;
#endif
		if (!captured || !view || fullscreen)
			return;

		G3D::Vector2 pos;
		view->getCursorPos(&pos);
		if (pos.x < 0 || pos.y < 0 || pos.x >= view->width || pos.y >= view->height)
			setCaptured(false);
	}

	// Sends the held-back key without text: nothing typed followed it.
	void flushPendingKey()
	{
		if (!pendingKey.valid)
			return;
		pendingKey.valid = false;
		if (view)
			view->handleKey(RobloxView::KEY_DOWN, pendingKey.key, pendingKey.mod);
	}

	// Text the page collected itself (web: an emoji picker, an input method,
	// a paste into the page's hidden text field).
	void typeText(const std::string& text)
	{
		flushPendingKey();
		if (view)
			view->handleText(text);
	}

	void handleEvent(const SDL_Event& e)
	{
		if (!view)
			return;

		if (e.type != SDL_TEXTINPUT)
		{
			// The text of a key always comes straight after it.
			flushPendingKey();
			if (e.type != SDL_KEYDOWN)
				repeatingText = false;
		}

		switch (e.type)
		{
		case SDL_WINDOWEVENT:
			switch (e.window.event)
			{
			case SDL_WINDOWEVENT_SIZE_CHANGED:
			{
				int w = 0, h = 0;
				SDL_GL_GetDrawableSize(window, &w, &h);
				view->setBounds(w, h);
				break;
			}
			case SDL_WINDOWEVENT_FOCUS_GAINED:
				focused = true;
				view->handleFocus(true);
				break;
			case SDL_WINDOWEVENT_FOCUS_LOST:
				focused = false;
				setCaptured(false);
				view->handleFocus(false);
				break;
			case SDL_WINDOWEVENT_ENTER:
				if (focused)
					setCaptured(true);
				break;
			default:
				break;
			}
			break;

		case SDL_MOUSEMOTION:
		case SDL_MOUSEBUTTONDOWN:
		case SDL_MOUSEBUTTONUP:
		case SDL_MOUSEWHEEL:
			// A console has no mouse.
			if (console)
				break;
			handleMouseEvent(e);
			break;

		case SDL_TEXTINPUT:
		{
			// The console's shell takes no typing: there the keys are a
			// gamepad, and its text boxes use the page's keyboard.
			if (console && console->isShellShown())
				break;

			std::string text = e.text.text;
			// Control characters are the keys' business (Enter, Tab...).
			if (text.empty() || (unsigned char) text[0] < 0x20 || text[0] == 0x7f)
			{
				flushPendingKey();
				break;
			}
			if (pendingKey.valid)
			{
				pendingKey.valid = false;
				view->handleKey(RobloxView::KEY_DOWN, pendingKey.key, pendingKey.mod, text);
			}
			else if (repeatingText)
			{
				if (text.size() > 1)
					view->handleText(text);
			}
			else
				view->handleText(text);
			repeatingText = false;
			break;
		}

		case SDL_KEYDOWN:
		case SDL_KEYUP:
		{
			if (e.key.repeat)
			{
				repeatingText = e.type == SDL_KEYDOWN;
				break;
			}
			repeatingText = false;

			// Alt+Enter and F11 toggle fullscreen, as on Windows.
			if (e.type == SDL_KEYDOWN &&
				(e.key.keysym.sym == SDLK_F11 || (e.key.keysym.sym == SDLK_RETURN && (e.key.keysym.mod & KMOD_ALT))))
			{
				toggleFullScreen();
				break;
			}

			if (handleConsoleKey(e))
				break;

			RBX::KeyCode key = toRbxKey(e.key.keysym);
			if (key == RBX::SDLK_UNKNOWN)
				break;

			const RBX::ModCode mod = toRbxMod(e.key.keysym.mod);
			const bool mayType = (key >= 32 && key < 127) && !(e.key.keysym.mod & (KMOD_CTRL | KMOD_GUI));
			if (e.type == SDL_KEYDOWN && mayType && SDL_IsTextInputActive())
			{
				pendingKey.valid = true;
				pendingKey.key = key;
				pendingKey.mod = mod;
				break;
			}
			view->handleKey(e.type == SDL_KEYDOWN ? RobloxView::KEY_DOWN : RobloxView::KEY_UP, key, mod);
			break;
		}

		case SDL_CONTROLLERDEVICEADDED:
		case SDL_CONTROLLERDEVICEREMOVED:
		case SDL_CONTROLLERBUTTONDOWN:
		case SDL_CONTROLLERBUTTONUP:
		case SDL_CONTROLLERAXISMOTION:
		{
			boost::mutex::scoped_lock lock(controllerEventMutex);
			controllerEvents.push_back(e);
			break;
		}

		default:
			break;
		}
	}

	void handleMouseEvent(const SDL_Event& e)
	{
		switch (e.type)
		{
		case SDL_MOUSEMOTION:
#ifdef __EMSCRIPTEN__
			if (!captured)
				setCaptured(true);
			view->handleMouse(RobloxView::MOUSE_MOVE, e.motion.xrel, e.motion.yrel, toRbxMod(SDL_GetModState()));
			// Without pointer lock the page's pointer is visible to the player
			// (as the engine's cursor); keep the two in the same place.
			if (!pointerLocked())
				view->syncCursor((int) (e.motion.x * drawableScale()), (int) (e.motion.y * drawableScale()));
#else
			if (captured)
				view->handleMouse(RobloxView::MOUSE_MOVE, e.motion.xrel, e.motion.yrel, toRbxMod(SDL_GetModState()));
			else if (focused)
				setCaptured(true);
#endif
			break;

		case SDL_MOUSEBUTTONDOWN:
		case SDL_MOUSEBUTTONUP:
		{
			const bool down = e.type == SDL_MOUSEBUTTONDOWN;
			if (down && !captured)
				setCaptured(true);
#ifdef __EMSCRIPTEN__
			if (!pointerLocked())
				view->syncCursor((int) (e.button.x * drawableScale()), (int) (e.button.y * drawableScale()));
#endif

			G3D::Vector2 pos;
			view->getCursorPos(&pos);

			RobloxView::EventType type;
			if (e.button.button == SDL_BUTTON_LEFT)
				type = down ? RobloxView::MOUSE_LEFT_BUTTON_DOWN : RobloxView::MOUSE_LEFT_BUTTON_UP;
			else if (e.button.button == SDL_BUTTON_RIGHT)
				type = down ? RobloxView::MOUSE_RIGHT_BUTTON_DOWN : RobloxView::MOUSE_RIGHT_BUTTON_UP;
			else if (e.button.button == SDL_BUTTON_MIDDLE)
				type = down ? RobloxView::MOUSE_MIDDLE_BUTTON_DOWN : RobloxView::MOUSE_MIDDLE_BUTTON_UP;
			else
				break;

			view->handleMouse(type, (int) pos.x, (int) pos.y, toRbxMod(SDL_GetModState()));
			break;
		}

		case SDL_MOUSEWHEEL:
		{
			// Windows wheel units: 120 per notch.
			int y = e.wheel.direction == SDL_MOUSEWHEEL_FLIPPED ? -e.wheel.y : e.wheel.y;
			if (y == 0)
				break;
			G3D::Vector2 pos;
			view->getCursorPos(&pos);
			view->handleScrollWheel(y > 0 ? 120.f : -120.f, (int) pos.x, (int) pos.y);
			break;
		}

		default:
			break;
		}
	}
};

// One pass of the main loop: window events, then whatever other threads
// marshalled here (rendering among it).  False once the player should exit.
bool runFrame(Player& player, bool wait)
{
	if (!player.isRunning() || LinuxApp::quitRequested())
		return false;

	SDL_Event e;
	if (wait ? SDL_WaitEventTimeout(&e, 16) : SDL_PollEvent(&e))
	{
		do
		{
			if (e.type == SDL_QUIT)
				LinuxApp::requestQuit();
			else if (e.type != LinuxApp::wakeEventType())
				player.handleEvent(e);
		}
		while (SDL_PollEvent(&e));
		player.flushPendingKey();
	}

	LinuxApp::processAppEvents();
	player.updateCapture();
	return true;
}

void shutdown(Player* player)
{
	player->stop();
	delete player;
	LinuxApp::setEventsRunning(false);
	LinuxApp::processAppEvents();
	LinuxApp::globalShutdown();
}

#ifdef __EMSCRIPTEN__
struct WebLoop
{
	Player* player;
	SDL_Window* window;
} webLoop;

// The FPS unlocker. requestAnimationFrame runs at the display's refresh rate,
// which is what a cap of 60 wants; above it the loop runs off timers instead,
// so the render job's own cap (the in-game "Maximum Frame Rate") is what
// limits it. The choice is remembered for the next visit (player.js).
void applyFrameTiming()
{
	static int applied = -1;
	const int cap = RBX::GameBasicSettings::singleton().getFramerateCap();
	if (cap == applied)
		return;
	applied = cap;

	const float hz = RBX::GameBasicSettings::getFramerateCapHz();
	if (cap == RBX::GameBasicSettings::FRAMERATE_CAP_60)
		emscripten_set_main_loop_timing(EM_TIMING_RAF, 1);
	else if (cap == RBX::GameBasicSettings::FRAMERATE_CAP_UNLIMITED)
		emscripten_set_main_loop_timing(EM_TIMING_SETIMMEDIATE, 0);
	else
		emscripten_set_main_loop_timing(EM_TIMING_SETTIMEOUT, std::max(1, (int) (1000.0f / hz)));

	EM_ASM({ try { localStorage.setItem('b16.fpsCap', String($0)); } catch (e) {} }, cap);
}

// The voice chat switch (GameSettings.HearVoiceChat), remembered like the
// frame rate cap: the browser keeps no settings file between visits.
void rememberHearVoiceChat()
{
	static int remembered = -1;
	const int hear = RBX::GameBasicSettings::singleton().getHearVoiceChat() ? 1 : 0;
	if (hear == remembered)
		return;
	remembered = hear;

	EM_ASM({ try { localStorage.setItem('b16.hearVoice', String($0)); } catch (e) {} }, hear);
}

// Hidden page: see LinuxApp::setPageHidden. A worker thread's timers are not
// throttled like the page's, and a call it posts to the main thread is
// delivered as a message, which a hidden page still gets promptly.
void runHiddenPageEvents(void*)
{
	if (LinuxApp::isPageHidden())
		LinuxApp::processAppEvents();
}

void hiddenPagePump()
{
	for (;;)
	{
		boost::this_thread::sleep(boost::posix_time::milliseconds(50));
		if (LinuxApp::isPageHidden())
			emscripten_async_run_in_main_runtime_thread(EM_FUNC_SIG_VI, (void*) &runHiddenPageEvents, (void*) NULL);
	}
}

EM_BOOL onVisibilityChange(int, const EmscriptenVisibilityChangeEvent* event, void*)
{
	LinuxApp::setPageHidden(event->hidden != 0);
	return EM_FALSE;
}

// The page's hidden text field hands over what the keyboard events cannot
// carry: an emoji picker's choice, an input method's word, a paste.
extern "C" EMSCRIPTEN_KEEPALIVE void rbxTypeText(const char* text)
{
	if (webLoop.player && text)
		webLoop.player->typeText(text);
}

void webFrame()
{
	applyFrameTiming();
	rememberHearVoiceChat();
	LinuxApp::beginFrame();
	if (runFrame(*webLoop.player, false))
	{
		// The canvas is presented when this callback returns: wait here for the
		// perform half of a frame whose prepare just ran, rather than showing it
		// one animation frame later (which halves the frame rate).
		LinuxApp::finishFrame(12.0);
		return;
	}

	emscripten_cancel_main_loop();
	shutdown(webLoop.player);
	SDL_DestroyWindow(webLoop.window);
	SDL_Quit();
	EM_ASM({ if (Module['onPlayerExit']) Module['onPlayerExit'](); });
}
#endif

} // namespace

int main(int argc, char** argv)
{
	std::set_terminate(&onTerminate);

	LaunchOptions options;
	std::string protocolLink;

	for (int i = 1; i < argc; ++i)
	{
		std::string arg = argv[i];
		std::string next = i + 1 < argc ? argv[i + 1] : "";

		if (arg == "-h" || arg == "--help") { printUsage(argv[0]); return 0; }
		else if (arg == "--play" || arg == "--app") { /* the only mode there is */ }
		else if ((arg == "-a" || arg == "--authenticationUrl") && i + 1 < argc) { options.authUrl = next; ++i; }
		else if ((arg == "-t" || arg == "--authenticationTicket") && i + 1 < argc) { options.authTicket = next; ++i; }
		else if ((arg == "-j" || arg == "--joinScriptUrl") && i + 1 < argc) { options.script = next; ++i; }
		else if (arg == "--base-url" && i + 1 < argc) { options.baseUrl = next; ++i; }
		else if (arg == "--content" && i + 1 < argc) { options.contentFolder = next; ++i; }
		else if (arg == "--file" && i + 1 < argc) { options.placeFile = next; ++i; }
		else if (arg == "--hash" && i + 1 < argc) { options.hash = next; ++i; }
		else if (arg == "--width" && i + 1 < argc) { options.width = atoi(next.c_str()); ++i; }
		else if (arg == "--height" && i + 1 < argc) { options.height = atoi(next.c_str()); ++i; }
		else if (arg == "--fullscreen") { options.fullscreen = true; }
		else if (arg == "--console") { options.console = true; }
		else if (arg == "--fps-cap" && i + 1 < argc) { options.framerateCap = atoi(next.c_str()); ++i; }
		else if (arg == "--hear-voice" && i + 1 < argc) { options.hearVoiceChat = atoi(next.c_str()) ? 1 : 0; ++i; }
		else if (arg == "--flog" && i + 1 < argc) { options.flogs.push_back(next); ++i; }
		else if (arg.find("://") != std::string::npos && arg.find("placeId=") != std::string::npos) { protocolLink = arg; }
		else if (arg.find("://") != std::string::npos && lower(arg).find("placeid=") != std::string::npos) { protocolLink = arg; }
		else { fprintf(stderr, "Unknown argument: %s\n\n", arg.c_str()); printUsage(argv[0]); return 1; }
	}

	const std::string exeFolder = executableFolder();
	readAppSettings(exeFolder, options);
	if (options.contentFolder.empty())
		options.contentFolder = exeFolder + "/content";

	if (options.baseUrl.empty())
	{
		fprintf(stderr, "No base URL: pass --base-url or put <BaseUrl> in %s/AppSettings.xml\n", exeFolder.c_str());
		return 1;
	}
	if (options.baseUrl[options.baseUrl.size() - 1] != '/')
		options.baseUrl += '/';

	if (!protocolLink.empty() && !parseProtocolLink(protocolLink, options))
	{
		fprintf(stderr, "Cannot read the launch link: %s\n", protocolLink.c_str());
		return 1;
	}

	if (options.script.empty() && options.placeFile.empty() && !options.console)
	{
		printUsage(argv[0]);
		return 1;
	}

	if (!boost::filesystem::is_directory(options.contentFolder))
	{
		fprintf(stderr, "Content folder not found: %s\n", options.contentFolder.c_str());
		return 1;
	}
	// The renderer finds shaders/ and PlatformContent/ as siblings of content/,
	// by appending "../shaders" to this, so it has to end in a separator.
	if (options.contentFolder[options.contentFolder.size() - 1] != '/')
		options.contentFolder += '/';

	if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMECONTROLLER) != 0)
	{
		fprintf(stderr, "SDL_Init failed: %s\n", SDL_GetError());
		return 1;
	}
	// Haptics are optional and fail on machines without evdev access.
	SDL_InitSubSystem(SDL_INIT_HAPTIC);
	// Sound (Linux/fmod_sdl) opens its device later, from the game's thread;
	// a machine without one plays silently.
	SDL_InitSubSystem(SDL_INIT_AUDIO);

	SDLGameController::pollEvent = &pollControllerEvent;
	LinuxApp::wakeEventType();

	SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
	SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
	SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
	SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
	SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
	SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
	SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
	SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
	SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

	Uint32 windowFlags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI;
	if (options.fullscreen)
		windowFlags |= SDL_WINDOW_FULLSCREEN_DESKTOP;

	// The Xbox app is laid out in pixels for the console's 1920x1080 frame,
	// and draws off its edges at any other size. So the console mode draws
	// exactly that, as the Xbox did; the page scales the canvas to the window.
	if (options.console)
	{
		options.width = 1920;
		options.height = 1080;
		windowFlags = SDL_WINDOW_OPENGL;
	}

	SDL_Window* window = SDL_CreateWindow("Better16", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
		options.width, options.height, windowFlags);
	if (!window)
	{
		fprintf(stderr, "SDL_CreateWindow failed: %s\n", SDL_GetError());
		SDL_Quit();
		return 1;
	}

	// Typed text arrives as SDL_TEXTINPUT (main.cpp pairs it with its key).
	SDL_StartTextInput();

	int exitCode = 0;
	try
	{
		LinuxApp::globalInit(options);

		Player* player = new Player(window, options);
		player->start(options);

#ifdef __EMSCRIPTEN__
		// The browser owns the loop: one frame per animation frame, and main()
		// returns while the runtime stays up.
		webLoop.player = player;
		webLoop.window = window;
		emscripten_set_visibilitychange_callback(NULL, EM_FALSE, &onVisibilityChange);
		{
			EmscriptenVisibilityChangeEvent visibility;
			if (emscripten_get_visibility_status(&visibility) == EMSCRIPTEN_RESULT_SUCCESS)
				LinuxApp::setPageHidden(visibility.hidden != 0);
		}
		boost::thread pump(&hiddenPagePump);
		pump.detach();
		emscripten_set_main_loop(&webFrame, 0, 0);
		return 0;
#else
		while (runFrame(*player, true))
			;

		shutdown(player);
#endif
	}
	catch (std::exception& e)
	{
		fprintf(stderr, "Fatal: %s\n", e.what());
		exitCode = 1;
	}

	SDL_DestroyWindow(window);
	SDL_Quit();
	return exitCode;
}
