# Spec: SSE Connection Fallback

## Requirement 1: SSE connection timeout triggers polling fallback

WHEN a new research session starts and `connectSse()` is called  
AND the EventSource connection does not open within 3 seconds  
THEN `startPolling(sessionId)` is invoked as a fallback data source  

## Requirement 2: Successful SSE connection cancels timeout

WHEN the EventSource `'open'` event fires within the timeout window  
THEN the timeout is cleared and polling is NOT started  
AND `_sseOpened` is set to `true` so the timeout knows the connection succeeded  

## Requirement 3: Disconnect cleans up timeout

WHEN `disconnectSse()` is called  
THEN any pending SSE timeout is cleared  
AND `_sseOpened` is reset to `false` for the next connection attempt  

## Requirement 4: Existing error handler still works as safety net

WHEN the EventSource `'error'` event fires with readyState CLOSED  
THEN polling is started (existing behavior preserved)  
