// Runs closures on the thread that owns the window (the SDL main loop), the way
// the Windows and Mac players marshal onto their UI thread.  Rendering must
// happen there because that is the thread the GL context lives on.
#pragma once

#include <map>

#include <boost/function.hpp>

#include "rbx/threadsafe.h"
#include "rbx/CEvent.h"

namespace RBX {

class FunctionMarshaller
{
	struct StaticData
	{
		std::map<unsigned, FunctionMarshaller*> windows;
		boost::recursive_mutex windowsCriticalSection;
	};
	SAFE_STATIC(StaticData, staticData)

	int refCount;
	unsigned threadID;
	explicit FunctionMarshaller(unsigned threadID);
	~FunctionMarshaller();

public:
	static FunctionMarshaller* GetWindow();
	static void ReleaseWindow(FunctionMarshaller* window);

	struct Closure
	{
		boost::function<void()>* f;
		RBX::CEvent* waitEvent;
	};

	static void handleAppEvent(Closure* closure);
	static void freeAppEvent(Closure* closure);

	// Runs job on the window thread. From any other thread it returns once the
	// job is queued; waitEvent, when given, is set after the job has run.
	void Execute(boost::function<void()> job, CEvent* waitEvent);
	void Submit(boost::function<void()> job);

	// Call this only from the window's thread.
	void ProcessMessages();
};

}
