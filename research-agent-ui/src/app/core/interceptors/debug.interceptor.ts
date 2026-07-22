import { Injectable } from '@angular/core';
import { HttpInterceptor, HttpRequest, HttpResponse, HttpHandler, HttpEvent } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

@Injectable()
export class DebugHttpInterceptor implements HttpInterceptor {

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (req.method === 'POST') {
      console.log(`[DebugHttp] POST ${req.url}`, JSON.stringify(req.body));
      console.log('[DebugHttp] Content-Type:', req.headers.get('Content-Type'));
      console.log('[DebugHttp] Accept:', req.headers.get('Accept'));
    }
    return next.handle(req).pipe(
      tap({
        next: event => {
          if (event instanceof HttpResponse) {
            console.log(`[DebugHttp] Response ${event.status} for ${req.url}`, JSON.stringify(event.body));
          }
        },
        error: err => {
          console.error(`[DebugHttp] Error for ${req.url}:`, err.status, JSON.stringify(err));
        }
      })
    );
  }
}
