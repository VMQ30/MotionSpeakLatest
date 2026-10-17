import React, { useState, useEffect, useMemo } from 'react';
import {
  View,
  Text,
  FlatList,
  SafeAreaView,
  TouchableOpacity,
  ActivityIndicator,
} from 'react-native';
import { Header } from '../components/Header';
import { SearchBar } from '../components/SearchBar';
import { CategoryCard } from '../components/CategoryCard';
import { WordListItem } from '../components/WordListItem';
import { BottomNavBar } from '../components/BottomNavBar';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DownloadAllModal } from '../components/DownloadAllModal';
import { IconDownload } from '../components/Icons';
import { Storage } from '../services/storage';
import { Api } from '../services/api';
import { NativeBridge } from '../services/nativeModules';
import { WordBankWord, CategoryItem, VocabularyFilter } from '../types';

interface WordBankScreenProps {
  onNavigate: (screen: string, params?: any) => void;
  initialSearch?: string;
  isDarkMode?: boolean;
}

export const WordBankScreen: React.FC<WordBankScreenProps> = ({
  onNavigate,
  initialSearch = '',
  isDarkMode = false,
}) => {
  const [searchQuery, setSearchQuery] = useState(initialSearch);
  const [filter, setFilter] = useState<VocabularyFilter>('ALL');
  const [words, setWords] = useState<WordBankWord[]>([]);
  const [categories, setCategories] = useState<CategoryItem[]>([]);
  const [favorites, setFavorites] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(false);

  // Bulk Download State
  const [showConfirmDownload, setShowConfirmDownload] = useState(false);
  const [downloadingAll, setDownloadingAll] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState({ completed: 0, total: 0, current: '' });

  useEffect(() => {
    setWords(Storage.getCachedWords());
    setCategories(Storage.getCachedCategories());
    setFavorites(Storage.getFavorites());

    const loadData = async () => {
      setLoading(true);
      const [fetchedWords, fetchedCats] = await Promise.all([
        Api.getWordBank(),
        Api.getCategories(),
      ]);
      setWords(fetchedWords);
      setCategories(fetchedCats);
      setLoading(false);
    };

    loadData();

    const sub = NativeBridge.onVideoDownloadProgress((event) => {
      setDownloadProgress({
        completed: event.completed,
        total: event.total,
        current: event.message || '',
      });
      if (event.status === 'finished' || event.status === 'failed') {
        setDownloadingAll(false);
      }
    });

    return () => {
      sub?.remove();
    };
  }, []);

  const handleToggleFavorite = (wordId: number) => {
    Storage.toggleFavorite(wordId);
    setFavorites(Storage.getFavorites());
  };

  const filteredCategories = useMemo(() => {
    return categories.map((cat) => ({
      ...cat,
      word_count: words.filter((w) => {
        const matchesCategory = w.category?.toLowerCase() === cat.name?.toLowerCase();
        if (filter === 'WORDS') return matchesCategory && w.vocabulary !== 'letters';
        if (filter === 'LETTERS') return matchesCategory && w.vocabulary === 'letters';
        return matchesCategory;
      }).length,
    }));
  }, [categories, words, filter]);

  const searchResults = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return [];
    return words.filter((w) => {
      const matchLabel = w.label?.toLowerCase().includes(q);
      const matchFilipino = w.filipino_translation?.toLowerCase().includes(q);
      const matchCategory = w.category?.toLowerCase().includes(q);

      const matchesText = matchLabel || matchFilipino || matchCategory;
      if (filter === 'WORDS') return matchesText && w.vocabulary !== 'letters';
      if (filter === 'LETTERS') return matchesText && w.vocabulary === 'letters';
      return matchesText;
    });
  }, [words, searchQuery, filter]);

  const isGridMode = searchQuery.trim().length === 0;

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <View className="flex-1 px-5 pt-3">
        {/* Header */}
        <Header
          title="Word Bank"
          subtitle="Filipino Sign Language words"
          rightAction={
            <TouchableOpacity
              onPress={() => setShowConfirmDownload(true)}
              className={`w-10 h-10 rounded-full items-center justify-center ${
                isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
              activeOpacity={0.7}
            >
              <IconDownload
                size={20}
                color={isDarkMode ? '#C9D8FA' : '#13306B'}
              />
            </TouchableOpacity>
          }
          isDarkMode={isDarkMode}
        />

        {/* Search Bar */}
        <View className="mt-2 mb-3">
          <SearchBar
            value={searchQuery}
            onChangeText={setSearchQuery}
            onClear={() => setSearchQuery('')}
            placeholder="Search words…"
            isDarkMode={isDarkMode}
          />
        </View>

        {isGridMode ? (
          /* Grid Mode: Filter Pills + Category Cards */
          <FlatList
            data={filteredCategories}
            keyExtractor={(item) => String(item.id || item.name)}
            numColumns={2}
            columnWrapperStyle={{ justifyContent: 'space-between' }}
            contentContainerStyle={{ paddingBottom: 110 }}
            ListHeaderComponent={
              <View className="mb-4">
                {/* Pills: All / Words / Letters */}
                <View className="flex-row items-center space-x-2 mb-4">
                  {(['ALL', 'WORDS', 'LETTERS'] as VocabularyFilter[]).map((pill) => {
                    const isActive = filter === pill;
                    return (
                      <TouchableOpacity
                        key={pill}
                        onPress={() => setFilter(pill)}
                        className={`px-4 py-1.5 rounded-full mr-2 ${
                          isActive
                            ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                            : isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                        }`}
                      >
                        <Text
                          className={`text-xs font-bold ${
                            isActive
                              ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                              : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                          }`}
                        >
                          {pill === 'ALL' ? 'All' : pill === 'WORDS' ? 'Words' : 'Letters'}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>

                <Text
                  className={`text-base font-bold mb-2 ${
                    isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                  }`}
                >
                  Categories
                </Text>
              </View>
            }
            renderItem={({ item }) => (
              <View className="w-[48%] mb-3">
                <CategoryCard
                  name={item.name}
                  wordCount={item.word_count}
                  onPress={() =>
                    onNavigate('CategoryWordList', { categoryName: item.name })
                  }
                  isDarkMode={isDarkMode}
                />
              </View>
            )}
          />
        ) : (
          /* List Mode: Filtered Search Results */
          <FlatList
            data={searchResults}
            keyExtractor={(item) => String(item.id)}
            contentContainerStyle={{ paddingBottom: 110 }}
            ListHeaderComponent={
              <Text
                className={`text-xs font-semibold mb-3 ${
                  isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                }`}
              >
                Showing {searchResults.length}{' '}
                {searchResults.length === 1 ? 'word' : 'words'}
              </Text>
            }
            renderItem={({ item }) => (
              <WordListItem
                word={item}
                isFavorite={favorites.has(item.id)}
                onPress={() => onNavigate('WordDetail', { wordId: item.id })}
                onToggleFavorite={() => handleToggleFavorite(item.id)}
                isDarkMode={isDarkMode}
              />
            )}
            ListEmptyComponent={
              <View className="items-center justify-center py-12">
                <Text
                  className={`text-base font-bold ${
                    isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                  }`}
                >
                  No words found
                </Text>
                <Text
                  className={`text-xs text-center mt-2 px-6 leading-5 ${
                    isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                  }`}
                >
                  Try a different search term or clear the filter.
                </Text>
              </View>
            }
          />
        )}
      </View>

      {/* Bulk Download Confirm Dialog */}
      <ConfirmDialog
        visible={showConfirmDownload}
        title="Download all demo videos?"
        message="This will save video demonstrations for all available signs so you can learn completely offline. Wi-Fi is recommended."
        confirmLabel="Download"
        variant="primary"
        onConfirm={() => {
          setShowConfirmDownload(false);
          setDownloadingAll(true);
          NativeBridge.downloadAllDemoVideos();
        }}
        onCancel={() => setShowConfirmDownload(false)}
        isDarkMode={isDarkMode}
      />

      {/* Bulk Download Progress Modal */}
      <DownloadAllModal
        visible={downloadingAll}
        completed={downloadProgress.completed}
        total={downloadProgress.total}
        currentWord={downloadProgress.current}
        onCancel={() => {
          NativeBridge.cancelDownloadAllVideos();
          setDownloadingAll(false);
        }}
        isDarkMode={isDarkMode}
      />

      {/* Persistent Bottom Nav */}
      <BottomNavBar
        currentTab="word_bank"
        onSelectTab={(tab) => {
          if (tab === 'word_bank') return;
          if (tab === 'home') onNavigate('Home');
          if (tab === 'translator') onNavigate('Translator');
          if (tab === 'history') onNavigate('History');
          if (tab === 'settings') onNavigate('Settings');
        }}
        isDarkMode={isDarkMode}
      />
    </SafeAreaView>
  );
};
