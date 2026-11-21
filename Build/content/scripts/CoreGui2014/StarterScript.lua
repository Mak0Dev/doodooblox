-- Creates all neccessary scripts for the gui on initial load, everything except build tools
-- Created by Ben T. 10/29/10
-- Please note that these are loaded in a specific order to diminish errors/perceived load time by user

-- The 2014 CoreGui (StarterScript version of 2014-08-15). The original fetched every script by
-- asset id through AddCoreScript; this engine only runs local core scripts, so each one now lives
-- under CoreGui2014/ and is started with AddCoreScriptLocal. RbxGui, RbxUtility and RbxStamper
-- come from the shared Libraries/, which still have every function the 2014 scripts call.
-- Started by the top-level StarterScript when UserGameSettings.CoreGuiVersion is Year2014.

local scriptContext = game:GetService("ScriptContext")
local touchEnabled = false
pcall(function() touchEnabled = game:GetService("UserInputService").TouchEnabled end)

local function waitForChild(instance, name)
	while not instance:FindFirstChild(name) do
		instance.ChildAdded:wait()
	end
end

waitForChild(game:GetService("CoreGui"),"RobloxGui")
local screenGui = game:GetService("CoreGui"):FindFirstChild("RobloxGui")

local function addCoreScript(name, parent)
	scriptContext:AddCoreScriptLocal("CoreGui2014/CoreScripts/" .. name, parent)
end

-- SettingsScript
addCoreScript("Settings", screenGui)

if not touchEnabled then
	-- ToolTipper  (creates tool tips for gui)
	addCoreScript("ToolTip", screenGui)
else
	addCoreScript("TouchControls", screenGui)
end

-- MainBotChatScript
addCoreScript("MainBotChatScript", screenGui)

-- Developer Console Script
addCoreScript("DeveloperConsole", screenGui)

-- Popup Script
addCoreScript("PopupScript", screenGui)
-- Friend Notification Script (probably can use this script to expand out to other notifications)
addCoreScript("NotificationScript", screenGui)
-- Chat script
addCoreScript("ChatScript", screenGui)

-- Player chat bubbles: with FFlagLuaBasedBubbleChat on, the engine skips its native
-- ChatOutput renderer, so this Lua renderer must run for any bubble (own messages
-- included) to appear above the player's head.
local luaBubbleChatOk, luaBubbleChatFlag = pcall(function() return settings():GetFFlag("LuaBasedBubbleChat") end)
print("BubbleChat switch: flagOk=", tostring(luaBubbleChatOk), "flag=", tostring(luaBubbleChatFlag))
if luaBubbleChatOk and luaBubbleChatFlag == true then
	addCoreScript("BubbleChat", screenGui)
end
-- Purchase Prompt Script
addCoreScript("PurchasePromptScript", screenGui)
-- Health Script
addCoreScript("HealthScript", screenGui)
-- Microphone button (ours; the 2016 CoreGui keeps it in the top bar)
addCoreScript("VoiceChatButton", screenGui)

-- The shift lock padlock (ControlGui.BottomLeftControl) is created by the character
-- camera script and is positioned above the settings button directly in
-- content/fonts/characterCameraScript.rbxmx.

if not touchEnabled then
	-- New Player List
	addCoreScript("PlayerListScript", screenGui)
elseif screenGui.AbsoluteSize.Y > 600 then
	-- New Player List
	addCoreScript("PlayerListScript", screenGui)
else
	delay(5, function()
		if screenGui.AbsoluteSize.Y >= 600 then
			-- New Player List
			addCoreScript("PlayerListScript", screenGui)
		end
	end)
end

-- Backpack Builder, creates most of the backpack gui
addCoreScript("BackpackScripts/BackpackBuilder", screenGui)

waitForChild(screenGui,"CurrentLoadout")
waitForChild(screenGui,"Backpack")
local Backpack = screenGui.Backpack

-- Manager handles all big backpack state changes, other scripts subscribe to this and do things accordingly
addCoreScript("BackpackScripts/BackpackManager", Backpack)
-- Backpack Gear (handles all backpack gear tab stuff)
addCoreScript("BackpackScripts/BackpackGear", Backpack)
-- Loadout Script, used for gear hotkeys
addCoreScript("BackpackScripts/LoadoutScript", screenGui.CurrentLoadout)

if touchEnabled then -- touch devices don't use same control frame
	-- only used for touch device button generation
	addCoreScript("ContextActionTouch", screenGui)

	waitForChild(screenGui, 'ControlFrame')
	waitForChild(screenGui.ControlFrame, 'BottomLeftControl')
	screenGui.ControlFrame.BottomLeftControl.Visible = false
end
