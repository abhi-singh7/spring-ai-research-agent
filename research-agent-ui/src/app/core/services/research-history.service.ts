import { Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { map } from 'rxjs/operators';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ResearchHistoryService {

  private readonly baseUrl = environment.apiUrl + '/api/research/history';

  constructor(private http: HttpClient) {
    console.log('ResearchHistoryService constructor');
  }

  // Reactive state
  sessions = signal<any[]>([]);
  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);
  searchTerm = '';

  loadHistory(page: number = 0, searchQuery?: string): void {
     console.log('loadHistory called');
    if (searchQuery !== undefined) this.searchTerm = searchQuery;

    let params = new HttpParams()
      .set('page', String(page))
      .set('size', '20');

    let url = this.baseUrl;
    // Use search endpoint if query is provided
    if (this.searchTerm && this.searchTerm.trim()) {
      url = environment.apiUrl + '/api/research/history/search';
      console.log('[ResearchHistoryService] Searching history with query:', this.searchTerm);
      params = params.set('query', this.searchTerm.trim());
    }

    const result$ = this.http.get<any>(url, { params }).pipe(
      map(response => {
        console.log('[ResearchHistoryService] API response:', JSON.stringify(response));
        const content = response?.content ?? [];
        console.log('[ResearchHistoryService] Loaded history - content length:', content.length);
        if (content.length > 0) {
          console.log('[ResearchHistoryService] First item:', JSON.stringify(content[0]));
        }

        // Ensure ResearchSession fields are available for the detail view
        const sessionsData = content.map((item: any) => ({
          ...item,
          finalReport: item.finalReport || null,
          createdAt: item.createdAt,
          updatedAt: item.updatedAt,
          completedAt: item.completedAt
        }));

        return {
          sessions: sessionsData,
          number: response?.number ?? 0,
          totalPages: response?.totalPages ?? 0,
          totalElements: response?.totalElements ?? 0,
        };
      })
    );

    result$.subscribe({
      next: (result) => {
        console.log('[ResearchHistoryService] Signal set - new sessions length:', result.sessions.length);
        this.sessions.set(result.sessions);
        this.currentPage.set(result.number);
        this.totalPages.set(result.totalPages);
        this.totalElements.set(result.totalElements);
      },
      error: (err) => {
        console.error('[ResearchHistoryService] Failed to load history:', err);
        this.sessions.set([]);
        this.currentPage.set(0);
        this.totalPages.set(0);
        this.totalElements.set(0);
      }
    });
  }

  /**
   * Delete a single research session from history.
   */
  deleteSession(sessionId: string): Observable<void> {
    const url = `${environment.apiUrl}/api/research/history/${sessionId}`;
    return this.http.delete<void>(url);
  }

  /**
   * Bulk delete multiple research sessions from history.
   */
  bulkDeleteSessions(sessionIds: string[]): Observable<void> {
    const url = `${environment.apiUrl}/api/research/history/bulk-delete`;
    return this.http.post<void>(url, sessionIds);
  }

}
