-- Creates all neccessary scripts for the gui on initial load, everything except build tools
-- Created by Ben T. 10/29/10
-- Please note that these are loaded in a specific order to diminish errors/perceived load time by user

-- 2014 CoreGui switch: this project ships the 2014-08-15 CoreGui under
-- content/scripts/CoreGui2014 and it is the default. Only an explicit "Default"
-- UserSettings().GameSettings.CoreGuiVersion sends us down the modern path.
local okService, service = pcall(function() return game:GetService("UserGameSettings") end)
if (not okService or not service) and UserSettings then
	local okGlobal, globalService = pcall(function() return UserSettings() end)
	if okGlobal then
		service = globalService
	end
end

local coreGuiRawValue = nil
if service then
	local okRead, readValue = pcall(function() return service.CoreGuiVersion end)
	if okRead then
		coreGuiRawValue = readValue
	end
end

local useCoreGui2014 = true
if coreGuiRawValue ~= nil then
	local raw = tostring(coreGuiRawValue)
	if coreGuiRawValue == "Default" or raw == "Default" or raw == "Enum.CoreGuiVersion.Default" or raw == "CoreGuiVersion.Default" or (type(coreGuiRawValue) == "number" and coreGuiRawValue == 0) then
		useCoreGui2014 = false
	end
end

print("CoreGuiVersion switch: rawValue=", tostring(coreGuiRawValue), "use2014=", tostring(useCoreGui2014))

if useCoreGui2014 then
	-- argument 2 must be an actual Instance (the bridge rejects nil); the engine's own
	-- default parents the starter to ScriptContext, so mirror that
	local coreScriptContext = game:GetService("ScriptContext")
	coreScriptContext:AddCoreScriptLocal("CoreGui2014/StarterScript", coreScriptContext)
	return
end

local scriptContext = game:GetService("ScriptContext")
local touchEnabled = game:GetService("UserInputService").TouchEnabled

local RobloxGui = game:GetService("CoreGui"):WaitForChild("RobloxGui")

local soundFolder = Instance.new("Folder")
soundFolder.Name = "Sounds"
soundFolder.Parent = RobloxGui

-- TopBar
local topbarSuccess, topbarFlagValue = pcall(function() return settings():GetFFlag("UseInGameTopBar") end)
local useTopBar = (topbarSuccess and topbarFlagValue == true)
if useTopBar then
	scriptContext:AddCoreScriptLocal("CoreScripts/Topbar", RobloxGui)
end

-- SettingsScript
local luaControlsSuccess, luaControlsFlagValue = pcall(function() return settings():GetFFlag("UseLuaCameraAndControl") end)

-- MainBotChatScript (the Lua part of Dialogs)
scriptContext:AddCoreScriptLocal("CoreScripts/MainBotChatScript2", RobloxGui)

-- Developer Console Script
scriptContext:AddCoreScriptLocal("CoreScripts/DeveloperConsole", RobloxGui)

-- In-game notifications script
scriptContext:AddCoreScriptLocal("CoreScripts/NotificationScript2", RobloxGui)

-- Chat script
if useTopBar then
	spawn(function() require(RobloxGui.Modules.Chat) end)
	spawn(function() require(RobloxGui.Modules.PlayerlistModule) end)
end

local luaBubbleChatSuccess, luaBubbleChatFlagValue = pcall(function() return settings():GetFFlag("LuaBasedBubbleChat") end)
if luaBubbleChatSuccess and luaBubbleChatFlagValue then
	scriptContext:AddCoreScriptLocal("CoreScripts/BubbleChat", RobloxGui)
end

-- Purchase Prompt Script
scriptContext:AddCoreScriptLocal("CoreScripts/PurchasePromptScript2", RobloxGui)

-- Health Script
if not useTopBar then
	scriptContext:AddCoreScriptLocal("CoreScripts/HealthScript", RobloxGui)
end

do -- Backpack!
	spawn(function() require(RobloxGui.Modules.BackpackScript) end)
end

if useTopBar then
	scriptContext:AddCoreScriptLocal("CoreScripts/VehicleHud", RobloxGui)
end

scriptContext:AddCoreScriptLocal("CoreScripts/GamepadMenu", RobloxGui)

if touchEnabled then -- touch devices don't use same control frame
	-- only used for touch device button generation
	scriptContext:AddCoreScriptLocal("CoreScripts/ContextActionTouch", RobloxGui)

	RobloxGui:WaitForChild("ControlFrame")
	RobloxGui.ControlFrame:WaitForChild("BottomLeftControl")
	RobloxGui.ControlFrame.BottomLeftControl.Visible = false
end
