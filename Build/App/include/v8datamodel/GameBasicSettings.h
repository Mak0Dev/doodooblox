#pragma once

#include "V8DataModel/GlobalSettings.h"
#include "V8DataModel/GameSettings.h"

namespace RBX
{
	extern const char *const sGameBasicSettings;
	class GameBasicSettings
		: public GlobalBasicSettingsItem<GameBasicSettings, sGameBasicSettings>
	{
		typedef GlobalBasicSettingsItem<GameBasicSettings, sGameBasicSettings> Super;
	public:
		enum ControlMode {CONTROL_CLASSIC = 0, CONTROL_MOUSELOCK = 1, CONTROL_HYBRID = 2, CONTROL_CAMLOCK = 3, CONTROL_MOUSEPAN = 4};
		enum RenderQualitySetting {QUALITY_AUTO = 0, QUALITY_1 = 1, QUALITY_2 = 2, QUALITY_3 = 3, QUALITY_4 = 4, QUALITY_5 = 5, QUALITY_6 = 6, QUALITY_7 = 7, QUALITY_8 = 8, QUALITY_9 = 9, QUALITY_10 = 10}; 
		enum CameraMode {CAMERA_MODE_DEFAULT = 0, CAMERA_MODE_CLASSIC = 1, CAMERA_MODE_FOLLOW = 2};
		enum TouchCameraMovementMode {
			TOUCH_CAMERA_MOVEMENT_MODE_DEFAULT = 0, 
			TOUCH_CAMERA_MOVEMENT_MODE_CLASSIC = 1, 
			TOUCH_CAMERA_MOVEMENT_MODE_FOLLOW = 2 };
		enum ComputerCameraMovementMode {
			COMPUTER_CAMERA_MOVEMENT_MODE_DEFAULT = 0, 
			COMPUTER_CAMERA_MOVEMENT_MODE_CLASSIC = 1, 
			COMPUTER_CAMERA_MOVEMENT_MODE_FOLLOW = 2};
		enum TouchMovementMode { 
			TOUCH_MOVEMENT_MODE_DEFAULT = 0, 
			TOUCH_MOVEMENT_MODE_THUMBSTICK = 1, 
			TOUCH_MOVEMENT_MODE_DPAD = 2, 
			TOUCH_MOVEMENT_MODE_THUMBPAD = 3, 
			TOUCH_MOVEMENT_MODE_CLICK_TO_MOVE = 4 };
		enum ComputerMovementMode { 
			COMPUTER_MOVEMENT_MODE_DEFAULT = 0, 
			COMPUTER_MOVEMENT_MODE_KBD_MOUSE = 1, 
			COMPUTER_MOVEMENT_MODE_CLICK_TO_MOVE = 2};
		enum RotationType {
			ROTATION_TYPE_MOVEMENT_RELATIVE = 0,
			ROTATION_TYPE_CAMERA_RELATIVE = 1};

		// Upper limit for the render job. Persisted with the rest of the user's
		// settings, so an unlocked frame rate survives a restart.
		enum FramerateCap {
			FRAMERATE_CAP_60 = 0,
			FRAMERATE_CAP_90 = 1,
			FRAMERATE_CAP_120 = 2,
			FRAMERATE_CAP_180 = 3,
			FRAMERATE_CAP_240 = 4,
			FRAMERATE_CAP_UNLIMITED = 5};

		enum CoreGuiVersion {
			CORE_GUI_VERSION_DEFAULT = 0,
			CORE_GUI_VERSION_YEAR_2014 = 1};

		// What "Unlimited" resolves to. High enough that computeStandardSleepTime()
		// always asks for a zero sleep, but still finite so nothing divides by infinity.
		static const float unlimitedFramerateHz;

		static Reflection::PropDescriptor<GameBasicSettings, float> prop_masterVolume;

		GameBasicSettings();

		ControlMode getControlMode() const { return controlMode; }
		void setControlMode(ControlMode setting);

		CameraMode getCameraMode() const { return cameraMode; }
		GameBasicSettings::CameraMode getCameraModeWithDefault() const;
		void setCameraMode(CameraMode setting);

		TouchCameraMovementMode getTouchCameraMovementMode() const { return touchCameraMovementMode; }
		void setTouchCameraMovementMode(TouchCameraMovementMode setting);
		bool getTouchCameraMovementModeModified() const { return touchCameraMovementModeModified; }
		void setTouchCameraMovementModeModified(bool setting);

		ComputerCameraMovementMode getComputerCameraMovementMode() const { return computerCameraMovementMode; }
		void setComputerCameraMovementMode(ComputerCameraMovementMode setting);
		bool getComputerCameraMovementModeModified() const { return computerCameraMovementModeModified; }
		void setComputerCameraMovementModeModified(bool setting);

		TouchMovementMode getTouchMovementMode() const { return touchMoveMode; }
		void setTouchMovementMode(TouchMovementMode setting);
		bool getTouchMovementModeModified() const { return touchMoveModeModeModified; }
		void setTouchMovementModeModified(bool setting);

		ComputerMovementMode getComputerMovementMode() const { return computerMoveMode; }
		void setComputerMovementMode(ComputerMovementMode setting);
		bool getComputerMovementModeModified() const { return computerMoveModeModeModified; }
		void setComputerMovementModeModified(bool setting);

		RotationType getRotationType() const;
		void setRotationType(RotationType setting);

		CoreGuiVersion getCoreGuiVersion() const { return coreGuiVersion; }
		void setCoreGuiVersion(CoreGuiVersion setting);

		void setMouseLock(bool isLocked);
		bool isMouseLocked() const { return mouseLocked; }

		void setCanMousePan(bool canPan) { canMousePan = canPan; }
		bool getCanMousePan() { return canMousePan; }

		void setFreeLook(bool canLook) { freeLook = canLook; }
		bool getFreeLook() { return freeLook; }

		bool inClassicMode()	{ return controlMode == CONTROL_CLASSIC; }
		bool inMouseLockMode()	{ return controlMode == CONTROL_MOUSELOCK; }
		bool inHybridMode()		{ return controlMode == CONTROL_HYBRID; }
		bool inCamlockMode()	{ return controlMode == CONTROL_CAMLOCK; }
		bool inMousepanMode()	{ return controlMode == CONTROL_MOUSEPAN; }
		
		bool mouseLockedInMouseLockMode()	{ return inMouseLockMode() && isMouseLocked(); }
		bool camLockedInCamLockMode()		{ return inCamlockMode() && !getFreeLook(); }

		bool getTutorialState(std::string tutorialId);
		void setTutorialState(std::string tutorialId, bool value);

		std::string getCompletedTutorials() const;
		void setCompletedTutorials(std::string value);

		GameSettings::UploadSetting getUploadVideoSetting() const { return uploadVideos; }
		void setUploadVideoSetting(GameSettings::UploadSetting setting);

		GameSettings::UploadSetting getPostImageSetting() const { return uploadScreenshots; }
		void setPostImageSetting(GameSettings::UploadSetting setting);

		RenderQualitySetting getRenderQuality() const { return renderQualitySetting; }
		void setRenderQuality(RenderQualitySetting value);

		FramerateCap getFramerateCap() const { return framerateCap; }
		void setFramerateCap(FramerateCap value);

		static float framerateCapToHz(FramerateCap value);

		// Read by the render jobs on the scheduler thread once per frame, so this is
		// a plain static rather than a trip through singleton().
		static float getFramerateCapHz() { return framerateCapHz; }

		bool getAllTutorialsDisabled() const { return allTutorialsDisabled; }
		void setAllTutorialsDisabled(bool value);

		bool getFullScreenConst() const { return fullscreen; }
		bool getFullScreen() { return fullscreen; }
		void setFullScreen(bool value) 
		{ 
			if(value != fullscreen)
			{
				fullscreen = value; 
				fullscreenChangedSignal(value);
			}
		}

		Vector2 getStartScreenPos() const { return startScreenPos; }
		void setStartScreenPos(Vector2 value);

		Vector2 getStartScreenSize() const { return startScreenSize; }
		void setStartScreenSize(Vector2 value);

		bool getStartMaximized() const { return startMaximized; }
		void setStartMaximized(bool value);

		float getMasterVolume() const { return masterVolume; }
		void setMasterVolume(float value);

		float getMouseSensitivity() const;
		void setMouseSensitivity(float value);

		bool inStudioMode() { return studio; }
		void setStudioMode(bool value) 
		{
			if(value != studio)
			{
				studioModeChangedSignal(value);
				studio = value; 
			}
		}

		bool getUsedHideHudShortcut() const { return usedHideHudShortcut; }
		void setUsedHideHudShortcut(bool value) { usedHideHudShortcut = value; }

		std::string getGoogleAnalyticsClientId() const;
		void setGoogleAnalyticsClientId(const std::string& id);
		
		/*override*/ void reset();
		/*override*/ void verifySetParent(const Instance* instance) const;

		void recordSettingsInGA(bool touchEnabled) const;

		rbx::signal<void(bool)> fullscreenChangedSignal;
		rbx::signal<void(bool)> studioModeChangedSignal;

	private:	
		ControlMode controlMode;
		RenderQualitySetting renderQualitySetting;
		FramerateCap framerateCap;
		static float framerateCapHz;
		CameraMode cameraMode;
		TouchCameraMovementMode touchCameraMovementMode;
		bool touchCameraMovementModeModified;
		ComputerCameraMovementMode computerCameraMovementMode;
		bool computerCameraMovementModeModified;
		TouchMovementMode touchMoveMode;
		bool touchMoveModeModeModified;
		ComputerMovementMode computerMoveMode;
		bool computerMoveModeModeModified;
		RotationType rotationType;
		CoreGuiVersion coreGuiVersion;

		bool mouseLocked;
		bool canMousePan;
		bool freeLook;
		GameSettings::UploadSetting uploadVideos;
		GameSettings::UploadSetting uploadScreenshots;
		bool usedHideHudShortcut;

		bool fullscreen;
		bool studio;

		Vector2 startScreenPos;
		Vector2 startScreenSize;

		bool startMaximized;

		float masterVolume;
		float mouseSensitivity;

		std::map<std::string, bool> tutorialState;
		bool allTutorialsDisabled;

		std::string googleAnalyticsClientId;
	};

}
