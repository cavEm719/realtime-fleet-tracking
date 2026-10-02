"""Two visible attempts for a single model request, never replay a whole write-bearing turn."""
import time
import urllib.error


def transient(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code == 429 or 500 <= error.code <= 599
    return getattr(error, 'transient', False)


def call_twice(call, record, sleep=time.sleep):
    for attempt in (1,2):
        try:
            result=call()
            record(dict(attempt=attempt,success=True,response=result))
            return result
        except Exception as error:
            record(dict(attempt=attempt,success=False,error=str(error),usageUnknown=getattr(error, "usageUnknown", True)))
            if attempt==2 or not transient(error):
                raise
            sleep(.5)
