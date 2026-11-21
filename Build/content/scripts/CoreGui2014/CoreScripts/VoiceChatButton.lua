-- Microphone button for the 2014 CoreGui. Not part of the 2014 original: voice chat is ours,
-- and in the 2016 CoreGui this button lives in the top bar (CoreScripts/Topbar.lua), which the
-- 2014 set does not have. Same rules as there: shown while the player may speak (CanUseMic)
-- and has the microphone switched on in the settings (MicEnabled); a click only mutes and
-- unmutes.

local VoiceChatService = nil
pcall(function() VoiceChatService = game:GetService("VoiceChatService") end)
if not VoiceChatService then return end

local gui = script.Parent

local VOICE_CHAT_ICON_PATH = "rbxasset://textures/ui/VoiceChat/MicLight/"
local VOICE_LEVEL_THRESHOLDS = {
	{level = 0.85, image = "Unmuted100"},
	{level = 0.65, image = "Unmuted80"},
	{level = 0.45, image = "Unmuted60"},
	{level = 0.25, image = "Unmuted40"},
	{level = 0.08, image = "Unmuted20"},
}

local micButton = Instance.new("ImageButton")
micButton.Name = "VoiceChatButton"
micButton.Size = UDim2.new(0, 34, 0, 34)
micButton.Position = UDim2.new(0, 5, 0, 5)
micButton.BackgroundTransparency = 1
micButton.AutoButtonColor = false
micButton.Image = VOICE_CHAT_ICON_PATH .. "Muted.png"
micButton.Visible = false
micButton.RobloxLocked = true
micButton.Parent = gui

local function imageForLevel(level)
	for i = 1, #VOICE_LEVEL_THRESHOLDS do
		if level >= VOICE_LEVEL_THRESHOLDS[i].level then
			return VOICE_LEVEL_THRESHOLDS[i].image
		end
	end
	return "Unmuted0"
end

local function updateIcon()
	if VoiceChatService.Muted then
		micButton.Image = VOICE_CHAT_ICON_PATH .. "Muted.png"
	else
		micButton.Image = VOICE_CHAT_ICON_PATH .. imageForLevel(VoiceChatService.MicLevel) .. ".png"
	end
end

local function updateVisible()
	micButton.Visible = VoiceChatService.CanUseMic and VoiceChatService.MicEnabled
end

VoiceChatService.Changed:connect(function(property)
	if property == "Muted" or property == "MicLevel" then
		updateIcon()
	elseif property == "CanUseMic" or property == "MicEnabled" then
		updateVisible()
	end
end)

micButton.MouseButton1Click:connect(function()
	VoiceChatService.Muted = not VoiceChatService.Muted
end)

updateIcon()
updateVisible()
