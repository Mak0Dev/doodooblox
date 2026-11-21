#include "FunctionMarshaller.h"

#include "util/standardout.h"
#include "rbx/Boost.hpp"
#include "LinuxApp.h"

using namespace RBX;

FunctionMarshaller::FunctionMarshaller(unsigned threadID)
	: refCount(0)
	, threadID(threadID)
{
}

FunctionMarshaller::~FunctionMarshaller()
{
}

FunctionMarshaller* FunctionMarshaller::GetWindow()
{
	// Share a common FunctionMarshaller in a given thread.
	boost::recursive_mutex::scoped_lock lock(staticData().windowsCriticalSection);
	unsigned threadID = GetCurrentThreadId();
	std::map<unsigned, FunctionMarshaller*>::iterator find = staticData().windows.find(threadID);
	if (find != staticData().windows.end())
	{
		find->second->refCount++;
		return find->second;
	}

	FunctionMarshaller* window = new FunctionMarshaller(threadID);
	staticData().windows[threadID] = window;
	window->refCount++;
	return window;
}

void FunctionMarshaller::ReleaseWindow(FunctionMarshaller* window)
{
	boost::recursive_mutex::scoped_lock lock(staticData().windowsCriticalSection);
	if (--window->refCount == 0)
	{
		staticData().windows.erase(window->threadID);
		delete window;
	}
}

void FunctionMarshaller::handleAppEvent(Closure* closure)
{
	RBX::CEvent* waitEvent = closure->waitEvent;
	try
	{
		(*closure->f)();
	}
	catch (std::exception& e)
	{
		StandardOut::singleton()->print(RBX::MESSAGE_ERROR, e);
	}

	freeAppEvent(closure);

	if (waitEvent)
		waitEvent->Set();
}

void FunctionMarshaller::freeAppEvent(Closure* closure)
{
	delete closure->f;
	delete closure;
}

void FunctionMarshaller::Execute(boost::function<void()> job, CEvent* waitEvent)
{
	if (threadID == GetCurrentThreadId())
	{
		job();
		if (waitEvent)
			waitEvent->Set();
		return;
	}

	// Execute is synchronous: the caller holds the DataModel lock the job relies
	// on.  A caller-supplied event lets it be released early (RenderJob::abortRender).
	CEvent localEvent(false);
	if (!waitEvent)
		waitEvent = &localEvent;

	Closure* closure = new Closure;
	closure->f = new boost::function<void()>(job);
	closure->waitEvent = waitEvent;
	LinuxApp::postAppEvent(closure);

	waitEvent->Wait();
}

void FunctionMarshaller::Submit(boost::function<void()> job)
{
	Closure* closure = new Closure;
	closure->f = new boost::function<void()>(job);
	closure->waitEvent = NULL;
	LinuxApp::postAppEvent(closure);
}

void FunctionMarshaller::ProcessMessages()
{
	LinuxApp::processAppEvents();
}
