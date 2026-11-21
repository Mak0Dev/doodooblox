// The desktop Linux player: process-wide state and the pieces the Mac player
// keeps in Roblox.cpp and its app delegate, minus Cocoa.
#pragma once

#include <string>
#include <vector>

#include <boost/function.hpp>
#include <boost/shared_ptr.hpp>

#include "FunctionMarshaller.h"

namespace RBX
{
	class Game;
}

struct LaunchOptions
{
	std::string contentFolder;   // .../content, holding fonts/, textures/, scripts/...
	std::string baseUrl;         // http://www.example.com/
	std::string authUrl;         // Login/Negotiate.ashx
	std::string authTicket;      // spent on authUrl?suggest=
	std::string script;          // Game/PlaceLauncher.ashx?... or Game/join.ashx?...
	std::string placeFile;       // a local .rbxl to open with no server
	std::string hash;            // DataModel::hash override
	std::vector<std::string> flogs; // Name=Value FastLog/FastFlag overrides, e.g. Graphics=1
	int width;
	int height;
	bool fullscreen;
	bool console;                // the Xbox app shell instead of one game (ConsoleShell)
	bool waitIfFull;             // keep asking PlaceLauncher while the game is full
	int framerateCap;            // GameBasicSettings::FramerateCap, -1 keeps the saved one
	int hearVoiceChat;           // GameBasicSettings::HearVoiceChat: 0 or 1, -1 keeps the saved one

	LaunchOptions() : width(1024), height(768), fullscreen(false), console(false), waitIfFull(true), framerateCap(-1), hearVoiceChat(-1) {}
};

namespace LinuxApp
{
	// Marshalling onto the main (SDL) thread.
	void postAppEvent(RBX::FunctionMarshaller::Closure* closure);
	void processAppEvents();
	void setEventsRunning(bool running);

	// SDL event type posted to wake the main loop when a closure is queued.
	unsigned wakeEventType();

	// Frame bookkeeping for the web loop, which has to get a frame's prepare
	// and perform into the same animation frame (the canvas is shown when the
	// callback returns). Set from the render closures on the main thread.
	void onRenderPrepared();
	void onFramePresented();
	// Runs queued closures until the frame prepared in this callback has been
	// drawn, or until the deadline (milliseconds from now).
	void finishFrame(double budgetMs);
	void beginFrame();

	// The web page is out of sight (another tab, a minimized window). The
	// browser then runs no animation frames at all, so nothing may wait on the
	// main loop: the render job stops drawing but keeps the DataModel stepping,
	// and marshalled closures are run by a timer of their own. Before this the
	// render job sat on the DataModel waiting for a frame that never came, the
	// game stopped answering the server's pings, and two minutes later the
	// server dropped the player ("Lost connection").
	void setPageHidden(bool hidden);
	bool isPageHidden();

	bool globalInit(const LaunchOptions& options);
	void globalShutdown();

	boost::shared_ptr<RBX::Game> createGame();
	void relinquishGame(boost::shared_ptr<RBX::Game>& game);

	// How a launch ended. The console mode's shell reports it to the app
	// (PlatformService.GameJoined), which says why a game did not start.
	enum LaunchResult
	{
		LAUNCH_JOINED,      // the join script is running
		LAUNCH_FAILED,      // the site or the join script could not be reached
		LAUNCH_REFUSED,     // PlaceLauncher said no
		LAUNCH_FULL,
		LAUNCH_USER_LEFT,   // the player being followed is not in a game
		LAUNCH_CANCELLED,   // the game was closed while this was going on
	};

	// Authenticates, asks PlaceLauncher for a server until it has one and runs the
	// join script. Blocks; call from a worker thread.
	LaunchResult launchPlace(boost::shared_ptr<RBX::Game> game, const LaunchOptions& options);

	// Opens a local place file with no server, as Play Solo would.
	void openPlaceFile(boost::shared_ptr<RBX::Game> game, const std::string& path);

	// Called on the main thread when TeleportService asks to move to another place.
	void setTeleportHandler(boost::function<void(const LaunchOptions&)> handler);

	void requestQuit();
	bool quitRequested();
}
