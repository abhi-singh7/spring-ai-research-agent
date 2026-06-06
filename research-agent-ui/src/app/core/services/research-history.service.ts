import { Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';

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

    this.http.get<any>(url, { params }).subscribe({
      next: (response) => {
        console.log('[ResearchHistoryService] API response:', JSON.stringify(response));
        const content = response?.content ?? [];
        console.log('[ResearchHistoryService] Loaded history - content length:', content.length);
        if (content.length > 0) {
          console.log('[ResearchHistoryService] First item:', JSON.stringify(content[0]));
        }
        // Ensure ResearchSession fields are available for the detail view
        this.sessions.set(content.map((item: any) => ({
          ...item,
          finalReport: item.finalReport || null,
          createdAt: item.createdAt,
          updatedAt: item.updatedAt,
          completedAt: item.completedAt
        })));
        console.log('[ResearchHistoryService] Signal set - new sessions length:', this.sessions().length);
        this.currentPage.set(response?.number ?? 0);
        this.totalPages.set(response?.totalPages ?? 0);
        this.totalElements.set(response?.totalElements ?? 0);
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

}
