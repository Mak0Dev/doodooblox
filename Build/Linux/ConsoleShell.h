// The web player's console mode (xplay.<domain>): the Xbox One app, run the
// way XboxClient/XboxService.cpp ran it. Two games side by side - the app
// shell (the home place, ScaledWorldv4.7.rbxl, and the Lua app in
// PlatformContent/durango/scripts/ui) and whichever game the shell starts -
// and one view that switches between them. The shell talks to the platform
// through PlatformService; this is its platform: the site instead of Xbox
// Live, the site's session instead of a gamertag.
#pragma once

#include <set>
#include <string>
#include <vector>

#include <boost/function.hpp>
#include <boost/shared_ptr.hpp>
#include <boost/thread/mutex.hpp>

#include "LinuxApp.h"
#include "v8datamodel/PlatformService.h"

namespace RBX
{
	class DataModel;
	class Game;
}

class ConsoleShell : public RBX::IPlatformAPI
{
public:
	// Puts a game in the window's view; called on the main thread. buildGui:
	// the game's first time in view.
	typedef boost::function<void(boost::shared_ptr<RBX::Game>, bool buildGui)> ShowFunction;

	ConsoleShell(const LaunchOptions& options, ShowFunction show);
	virtual ~ConsoleShell();

	// The shell's game, main thread, once: createShell makes it, and
	// startShell - once the view shows it - loads the home place, starts the
	// app's scripts and makes a local player. In that order, as the Xbox did:
	// the app asks for services the view is what creates (TextService).
	boost::shared_ptr<RBX::Game> createShell();
	void startShell();

	// Main thread: the game in view asked to leave (Leave in its menu). Back
	// to the shell, and the game is shut down. The shell itself never leaves.
	void leaveGame();

	// Main thread: TeleportService moves the game to another place.
	void teleport(const LaunchOptions& launch);

	// Main thread, at exit, after the view is gone: both games shut down.
	void shutdown();

	// Main thread: whether the shell is the game in view. The keyboard plays
	// a gamepad there.
	bool isShellShown() const { return shellShown; }

	// Main thread: a game is in view and still loading. Its loading screen
	// only answers B (cancel), and there is no in-game menu yet for Esc.
	bool isGameLoading() const;

	// The page's on-screen keyboard closed (console.js). accepted: the text is
	// what the player typed, else they cancelled.
	static void keyboardClosed(const std::string& text, bool accepted);

	// RBX::IPlatformAPI
	virtual RBX::AccountAuthResult performAuthorization(RBX::InputObject::UserInputType gamepadId, bool unLinkedCheck);
	virtual int performAccountLink(const std::string& accountName, const std::string& password, std::string* response);
	virtual int performUnlinkAccount(std::string* response);
	virtual int performSetRobloxCredentials(const std::string& accountName, const std::string& password, std::string* response);
	virtual RBX::AccountAuthResult performHasRobloxCredentials();
	virtual RBX::AccountAuthResult performHasLinkedAccount();
	virtual RBX::GameStartResult startGame3(RBX::GameJoinType joinType, int id);
	virtual void requestGameShutdown(bool teleport);
	virtual int netConnectionCheck();
	virtual int fetchFriends(RBX::InputObject::UserInputType gamepadId, std::string* result);
	virtual int popupHelpUI();
	virtual int launchPlatformUri(const std::string baseUri);
	virtual int popupPartyUI(RBX::InputObject::UserInputType gamepadId);
	virtual int popupProfileUI(RBX::InputObject::UserInputType gamepadId, std::string uid);
	virtual int popupAccountPickerUI(RBX::InputObject::UserInputType gamepadId);
	virtual void popupGameInviteUI();
	virtual void showKeyBoard(std::string& title, std::string& description, std::string& defaultText, unsigned keyboardType, RBX::DataModel* dm);
	virtual void setScreenResolution(double px, double py);
	virtual int fetchCatalogInfo(shared_ptr<RBX::Reflection::ValueArray> values);
	virtual int fetchInventoryInfo(shared_ptr<RBX::Reflection::ValueArray> values);
	virtual int getPlatformPartyMembers(shared_ptr<RBX::Reflection::ValueArray> result);
	virtual int getInGamePlayers(shared_ptr<RBX::Reflection::ValueArray> result);
	virtual RBX::PlatformPurchaseResult requestPurchase(const std::string& productId);
	virtual int getPMPCreatorId();
	virtual int getTitleId();
	virtual shared_ptr<const RBX::Reflection::ValueTable> getVersionIdInfo();
	virtual shared_ptr<const RBX::Reflection::ValueTable> getPlatformUserInfo();
	virtual RBX::AwardResult awardAchievement(const std::string& eventName);
	virtual RBX::AwardResult setHeroStat(const std::string& eventName, double* value);
	virtual void voiceChatSetMuteState(int userId, bool mute);
	virtual unsigned voiceChatGetState(int userId);

private:
	LaunchOptions options;
	ShowFunction show;

	boost::shared_ptr<RBX::Game> shell;

	// The game being played, or null. Guarded by gameMutex: startGame3 runs
	// on a PlatformService worker, leaving on the main thread.
	boost::mutex gameMutex;
	boost::shared_ptr<RBX::Game> game;

	// The view's side, on the main thread (syncView): what it should show
	// and what to shut down once it has left them, set from any thread.
	boost::mutex viewMutex;
	boost::shared_ptr<RBX::Game> viewTarget;
	std::vector<boost::shared_ptr<RBX::Game> > closing;

	// Main thread only.
	boost::shared_ptr<RBX::Game> shown;
	std::set<RBX::Game*> built; // games whose GUI the view has made
	bool shellShown;
	bool syncing;
	bool resync;

	// Who the site's session says the player is, once signed in.
	boost::mutex userMutex;
	int userId;
	std::string userName;

	void configureGame(boost::shared_ptr<RBX::Game> game);
	void requestView(boost::shared_ptr<RBX::Game> game);
	void requestClose(boost::shared_ptr<RBX::Game> game);
	void syncView();
	void muteShell(bool mute);
	void returnToShell(boost::shared_ptr<RBX::Game> leaving);

	// Signals on the shell's PlatformService, fired on its DataModel.
	void fireViewChanged(int viewType);
	void fireGameJoined(int result);
	void fireUserAccountChanged(int reason);

	RBX::AccountAuthResult readSession();
	void setLocalPlayer(int userId, const std::string& name);
};
