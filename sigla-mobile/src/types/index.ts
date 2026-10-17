export interface WordBankWord {
  id: number;
  label: string;
  description?: string | null;
  sign_type?: string;
  category: string;
  thumbnail_url?: string | null;
  video_url?: string | null;
  filipino_translation?: string | null;
  vocabulary?: 'words' | 'letters' | string | null;
}

export interface CategoryItem {
  id: number;
  name: string;
  description?: string | null;
  word_count: number;
}

export interface HomeCategory {
  name: string;
  wordCount: number;
}

export interface TranslationEntry {
  word: string;
  confidence: number;
  gestureType: string;
  timestamp: number;
}

export interface HistoryGroup {
  date: string;
  entries: TranslationEntry[];
}

export interface AppSettingsState {
  volume: number;
  voiceType: 'male' | 'female';
  isDarkMode: boolean;
  showFilipino: boolean;
  isFrontCamera: boolean;
  translationMode: 'tap' | 'live';
  isOnboardingDone: boolean;
  userName: string | null;
}

export interface ModelInfo {
  id: number;
  version_number: string;
  tflite_url?: string | null;
  motion_tflite_url?: string | null;
  labels_motion_url?: string | null;
  accuracy?: number | null;
  total_classes?: number | null;
  checksum?: string | null;
  deployed_at?: string | null;
}

export interface DeployedModels {
  words?: ModelInfo | null;
  letters?: ModelInfo | null;
}

export interface ModelResponse {
  model: ModelInfo;
  models?: DeployedModels | null;
  deployment_version?: string | null;
}

export type TabType = 'home' | 'word_bank' | 'translator' | 'history' | 'settings';
export type VocabularyFilter = 'ALL' | 'WORDS' | 'LETTERS';

export type ScreenType =
  | 'Splash'
  | 'Onboarding'
  | 'Home'
  | 'Translator'
  | 'WordBank'
  | 'CategoryWordList'
  | 'WordDetail'
  | 'History'
  | 'Settings';
