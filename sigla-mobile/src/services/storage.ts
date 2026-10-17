import { AppSettingsState, TranslationEntry, HistoryGroup, WordBankWord, CategoryItem } from '../types';
import { NativeModules, Platform } from 'react-native';

const SiglaBridge = NativeModules.SiglaBridge;

// Local in-memory state with disk persistence
class StorageService {
  private settings: AppSettingsState = {
    volume: 80,
    voiceType: 'female',
    isDarkMode: false,
    showFilipino: true,
    isFrontCamera: false,
    translationMode: 'tap',
    isOnboardingDone: false,
    userName: null,
  };

  private favorites: Set<number> = new Set();
  private history: TranslationEntry[] = [];
  private cachedWords: WordBankWord[] = [];
  private cachedCategories: CategoryItem[] = [];
  private initialized = false;

  constructor() {
    this.initFromNative();
  }

  public async initFromNative() {
    if (this.initialized) return;
    try {
      if (SiglaBridge?.getInitialData) {
        const data = await SiglaBridge.getInitialData();
        if (data.settings) {
          this.settings = { ...this.settings, ...data.settings };
        }
        if (Array.isArray(data.favorites)) {
          this.favorites = new Set(data.favorites);
        }
        if (Array.isArray(data.history)) {
          this.history = data.history;
        }
        if (Array.isArray(data.cachedWords)) {
          this.cachedWords = data.cachedWords;
        }
        if (Array.isArray(data.cachedCategories)) {
          this.cachedCategories = data.cachedCategories;
        }
      }
    } catch (e) {
      // In standalone or test mode, fallback to default in-memory state
    }
    this.initialized = true;
  }

  // ── Settings ─────────────────────────────────────────────────────────────
  public getSettings(): AppSettingsState {
    return { ...this.settings };
  }

  public updateSettings(partial: Partial<AppSettingsState>) {
    this.settings = { ...this.settings, ...partial };
    if (SiglaBridge?.saveSettings) {
      SiglaBridge.saveSettings(this.settings);
    }
  }

  public resetSettings() {
    this.settings = {
      volume: 80,
      voiceType: 'female',
      isDarkMode: false,
      showFilipino: true,
      isFrontCamera: false,
      translationMode: 'tap',
      isOnboardingDone: this.settings.isOnboardingDone,
      userName: this.settings.userName,
    };
    if (SiglaBridge?.saveSettings) {
      SiglaBridge.saveSettings(this.settings);
    }
  }

  // ── Favorites ────────────────────────────────────────────────────────────
  public getFavorites(): Set<number> {
    return new Set(this.favorites);
  }

  public isFavorite(wordId: number): boolean {
    return this.favorites.has(wordId);
  }

  public toggleFavorite(wordId: number): boolean {
    const isFav = this.favorites.has(wordId);
    if (isFav) {
      this.favorites.delete(wordId);
    } else {
      this.favorites.add(wordId);
    }
    if (SiglaBridge?.saveFavorites) {
      SiglaBridge.saveFavorites(Array.from(this.favorites));
    }
    return !isFav;
  }

  // ── History ──────────────────────────────────────────────────────────────
  public getHistory(): TranslationEntry[] {
    return [...this.history].sort((a, b) => b.timestamp - a.timestamp);
  }

  public addHistoryEntry(word: string, confidence: number, gestureType: string) {
    const entry: TranslationEntry = {
      word,
      confidence,
      gestureType,
      timestamp: Date.now(),
    };
    this.history.unshift(entry);
    if (this.history.length > 200) {
      this.history = this.history.slice(0, 200);
    }
    if (SiglaBridge?.addHistoryEntry) {
      SiglaBridge.addHistoryEntry(entry);
    }
  }

  public deleteHistoryAt(flatIndex: number) {
    if (flatIndex >= 0 && flatIndex < this.history.length) {
      this.history.splice(flatIndex, 1);
      if (SiglaBridge?.saveHistory) {
        SiglaBridge.saveHistory(this.history);
      }
    }
  }

  public clearHistory() {
    this.history = [];
    if (SiglaBridge?.clearHistory) {
      SiglaBridge.clearHistory();
    }
  }

  public getTranslatedToday(): number {
    const now = new Date();
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    return this.history.filter((e) => e.timestamp >= startOfDay).length;
  }

  public getGroupedHistory(): HistoryGroup[] {
    const sorted = this.getHistory();
    const groups: { [key: string]: TranslationEntry[] } = {};
    const now = new Date();
    const todayStr = this.formatDate(now.getTime());
    const yesterday = new Date(now.getTime() - 86400000);
    const yesterdayStr = this.formatDate(yesterday.getTime());

    for (const entry of sorted) {
      const entryDateStr = this.formatDate(entry.timestamp);
      let label = entryDateStr;
      if (entryDateStr === todayStr) {
        label = 'Today';
      } else if (entryDateStr === yesterdayStr) {
        label = 'Yesterday';
      }
      if (!groups[label]) {
        groups[label] = [];
      }
      groups[label].push(entry);
    }

    return Object.keys(groups).map((date) => ({
      date,
      entries: groups[date],
    }));
  }

  // ── Word Cache ───────────────────────────────────────────────────────────
  public getCachedWords(): WordBankWord[] {
    return [...this.cachedWords];
  }

  public setCachedWords(words: WordBankWord[]) {
    this.cachedWords = words;
    if (SiglaBridge?.saveCachedWords) {
      SiglaBridge.saveCachedWords(words);
    }
  }

  public getCachedCategories(): CategoryItem[] {
    return [...this.cachedCategories];
  }

  public setCachedCategories(categories: CategoryItem[]) {
    this.cachedCategories = categories;
    if (SiglaBridge?.saveCachedCategories) {
      SiglaBridge.saveCachedCategories(categories);
    }
  }

  // Helpers
  public formatDate(ts: number): string {
    const d = new Date(ts);
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${months[d.getMonth()]} ${String(d.getDate()).padStart(2, '0')}, ${d.getFullYear()}`;
  }

  public formatTime(ts: number): string {
    const d = new Date(ts);
    let hours = d.getHours();
    const minutes = String(d.getMinutes()).padStart(2, '0');
    const ampm = hours >= 12 ? 'PM' : 'AM';
    hours = hours % 12;
    hours = hours ? hours : 12;
    return `${hours}:${minutes} ${ampm}`;
  }
}

export const Storage = new StorageService();
