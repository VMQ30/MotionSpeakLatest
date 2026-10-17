import { WordBankWord, CategoryItem, ModelResponse } from '../types';
import { Storage } from './storage';

export const BASE_URL = 'https://siglaph.online/api/';

class ApiService {
  public async getWordBank(): Promise<WordBankWord[]> {
    try {
      const res = await fetch(`${BASE_URL}words/word-bank`);
      if (res.ok) {
        const data = await res.json();
        const words: WordBankWord[] = data.words || [];
        if (words.length > 0) {
          Storage.setCachedWords(words);
        }
        return words;
      }
    } catch (e) {
      // Offline fallback: load cached words
    }
    return Storage.getCachedWords();
  }

  public async getCategories(): Promise<CategoryItem[]> {
    try {
      const res = await fetch(`${BASE_URL}categories`);
      if (res.ok) {
        const data = await res.json();
        const categories: CategoryItem[] = data.categories || [];
        if (categories.length > 0) {
          Storage.setCachedCategories(categories);
        }
        return categories;
      }
    } catch (e) {
      // Offline fallback
    }
    return Storage.getCachedCategories();
  }

  public async getLatestModel(): Promise<ModelResponse | null> {
    try {
      const res = await fetch(`${BASE_URL}models/latest`);
      if (res.ok) {
        return await res.json();
      }
    } catch (e) {
      // Offline
    }
    return null;
  }
}

export const Api = new ApiService();
