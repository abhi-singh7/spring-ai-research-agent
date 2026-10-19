import { Injectable, signal } from '@angular/core';

/**
 * Owns the app's colour scheme. The dark scheme is a real Angular Material theme
 * (`styles.scss` re-declares every Material token under `:root.rp-theme-dark`), so
 * switching schemes is one class on the root element and no component has its own
 * `prefers-color-scheme` block.
 *
 * The OS preference alone is not enough: a user who is dark-mode-averse had no way to
 * opt out, so the choice ('auto' | 'light' | 'dark') is stored and replayed on reload.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {

  private static readonly DARK_CLASS = 'rp-theme-dark';
  private static readonly STORAGE_KEY = 'ra-theme';

  private readonly systemPreference = window.matchMedia('(prefers-color-scheme: dark)');
  private stored = 'auto';

  /** Effective scheme, bound by the toolbar control. */
  readonly dark = signal(false);
  /** The user's own choice — 'auto' means "whatever the system says". */
  readonly choice = signal('auto');

  constructor() {
    this.stored = this.readStoredPreference();
    this.dark.set(this.effectiveDark());
    this.choice.set(this.stored);
    this.syncRootClass();
    this.followSystemPreferenceChanges();
  }

  setPreference(preference: string): void {
    if (preference !== 'auto' && preference !== 'light' && preference !== 'dark') {
      return;
    }
    this.stored = preference;
    this.writeStoredPreference(preference);
    this.dark.set(this.effectiveDark());
    this.choice.set(preference);
    this.syncRootClass();
  }

  private effectiveDark(): boolean {
    if (this.stored === 'dark') return true;
    if (this.stored === 'light') return false;
    return this.systemPreference.matches;
  }

  /**
   * Material's token declarations are scoped to the theme class, so one class flip
   * restyles the toolbar, cards, chips, spinner and the report body at once.
   */
  private syncRootClass(): void {
    document.documentElement.classList.toggle(ThemeService.DARK_CLASS, this.dark());
  }

  private followSystemPreferenceChanges(): void {
    try {
      this.systemPreference.addEventListener('change', () => {
        if (this.stored !== 'auto') return;
        this.dark.set(this.systemPreference.matches);
        this.syncRootClass();
      });
    } catch {
      // Engines without MediaQueryList#addEventListener keep the boot-time value.
    }
  }

  private readStoredPreference(): string {
    try {
      const value = localStorage.getItem(ThemeService.STORAGE_KEY);
      if (value === 'light' || value === 'dark' || value === 'auto') return value;
    } catch {
      // No localStorage available: follow the system.
    }
    return 'auto';
  }

  private writeStoredPreference(preference: string): void {
    try {
      if (preference === 'auto') {
        localStorage.removeItem(ThemeService.STORAGE_KEY);
      } else {
        localStorage.setItem(ThemeService.STORAGE_KEY, preference);
      }
    } catch {
      // Persistence is best effort — the in-memory choice still applies this visit.
    }
  }
}
