import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, SafeAreaView } from 'react-native';
import { Header } from '../components/Header';
import { WordListItem } from '../components/WordListItem';
import { Storage } from '../services/storage';
import { WordBankWord } from '../types';

interface CategoryWordListScreenProps {
  categoryName: string;
  onBack: () => void;
  onNavigate: (screen: string, params?: any) => void;
  isDarkMode?: boolean;
}

export const CategoryWordListScreen: React.FC<CategoryWordListScreenProps> = ({
  categoryName,
  onBack,
  onNavigate,
  isDarkMode = false,
}) => {
  const [words, setWords] = useState<WordBankWord[]>([]);
  const [favorites, setFavorites] = useState<Set<number>>(new Set());

  useEffect(() => {
    const allWords = Storage.getCachedWords();
    const matched = allWords.filter(
      (w) => w.category?.toLowerCase() === categoryName?.toLowerCase()
    );
    setWords(matched);
    setFavorites(Storage.getFavorites());
  }, [categoryName]);

  const handleToggleFavorite = (wordId: number) => {
    Storage.toggleFavorite(wordId);
    setFavorites(Storage.getFavorites());
  };

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <View className="flex-1 px-5 pt-3">
        <Header
          title={categoryName}
          subtitle={`${words.length} ${words.length === 1 ? 'sign' : 'signs'}`}
          onBack={onBack}
          isDarkMode={isDarkMode}
        />

        <FlatList
          data={words}
          keyExtractor={(item) => String(item.id)}
          contentContainerStyle={{ paddingTop: 12, paddingBottom: 40 }}
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
            <View className="items-center justify-center py-16">
              <Text
                className={`text-sm ${
                  isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                }`}
              >
                No signs found in this category.
              </Text>
            </View>
          }
        />
      </View>
    </SafeAreaView>
  );
};
