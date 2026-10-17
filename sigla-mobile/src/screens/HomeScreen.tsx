import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  SafeAreaView,
  TouchableOpacity,
  RefreshControl,
} from 'react-native';
import { Storage } from '../services/storage';
import { Api } from '../services/api';
import { SearchBar } from '../components/SearchBar';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { StatCard } from '../components/StatCard';
import { CategoryCard } from '../components/CategoryCard';
import { RecentItem } from '../components/RecentItem';
import { BottomNavBar } from '../components/BottomNavBar';
import { IconHand } from '../components/Icons';
import { TabType, HomeCategory, TranslationEntry } from '../types';

interface HomeScreenProps {
  onNavigate: (screen: string, params?: any) => void;
  isDarkMode?: boolean;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  onNavigate,
  isDarkMode = false,
}) => {
  const [refreshing, setRefreshing] = useState(false);
  const [userName, setUserName] = useState<string | null>(null);
  const [wordsCount, setWordsCount] = useState(0);
  const [translatedToday, setTranslatedToday] = useState(0);
  const [favoritesCount, setFavoritesCount] = useState(0);
  const [categories, setCategories] = useState<HomeCategory[]>([]);
  const [recentList, setRecentList] = useState<TranslationEntry[]>([]);

  const loadDashboardData = useCallback(async () => {
    const settings = Storage.getSettings();
    setUserName(settings.userName);

    const history = Storage.getHistory();
    setRecentList(history.slice(0, 5));
    setTranslatedToday(Storage.getTranslatedToday());
    setFavoritesCount(Storage.getFavorites().size);

    // Categories and words
    const cachedWords = Storage.getCachedWords();
    const cachedCategories = Storage.getCachedCategories();
    setWordsCount(cachedWords.length);

    if (cachedCategories.length > 0) {
      setCategories(
        cachedCategories.slice(0, 6).map((c) => ({
          name: c.name,
          wordCount: c.word_count || cachedWords.filter((w) => w.category === c.name).length || 8,
        }))
      );
    }

    // Refresh from backend in background
    try {
      const [words, cats] = await Promise.all([
        Api.getWordBank(),
        Api.getCategories(),
      ]);
      setWordsCount(words.length);
      if (cats.length > 0) {
        setCategories(
          cats.slice(0, 6).map((c) => ({
            name: c.name,
            wordCount: c.word_count || words.filter((w) => w.category === c.name).length || 8,
          }))
        );
      }
    } catch (e) {
      // Offline fallback used
    }
  }, []);

  useEffect(() => {
    loadDashboardData();
  }, [loadDashboardData]);

  const onRefresh = async () => {
    setRefreshing(true);
    await loadDashboardData();
    setRefreshing(false);
  };

  const getGreeting = () => {
    const hour = new Date().getHours();
    let timeGreeting = 'Good morning';
    if (hour >= 12 && hour < 18) {
      timeGreeting = 'Good afternoon';
    } else if (hour >= 18) {
      timeGreeting = 'Good evening';
    }

    const title = userName ? `Hi ${userName}!` : 'Hello!';
    return { title, subtitle: timeGreeting };
  };

  const greeting = getGreeting();

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <ScrollView
        className="flex-1"
        contentContainerStyle={{ paddingBottom: 110 }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor={isDarkMode ? '#C9D8FA' : '#13306B'}
          />
        }
      >
        <View className="px-5 pt-4">
          {/* Top Greeting & Avatar */}
          <View className="flex-row items-center justify-between mb-4">
            <View className="flex-1">
              <Text
                className={`text-2xl font-bold ${
                  isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                }`}
                numberOfLines={1}
              >
                {greeting.title}
              </Text>
              <Text
                className={`text-xs mt-0.5 ${
                  isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                }`}
              >
                {greeting.subtitle}
              </Text>
            </View>

            {userName && (
              <View
                className={`w-10 h-10 rounded-full items-center justify-center ${
                  isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                }`}
              >
                <Text
                  className={`font-bold text-sm ${
                    isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                  }`}
                >
                  {userName.charAt(0).toUpperCase()}
                </Text>
              </View>
            )}
          </View>

          {/* Search Bar (Tapping navigates to Word Bank) */}
          <SearchBar
            readOnly
            onPress={() => onNavigate('WordBank', { focusSearch: true })}
            placeholder="Search FSL signs"
            isDarkMode={isDarkMode}
          />

          {/* Hero Translate Card */}
          <View className="mt-4">
            <Card variant="tint" isDarkMode={isDarkMode} className="p-5">
              <View className="flex-row items-center justify-between">
                <View className="flex-1 pr-3">
                  <Text
                    className={`text-xl font-bold ${
                      isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                    }`}
                  >
                    Start translating
                  </Text>
                  <Text
                    className={`text-xs mt-1 leading-5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    Tap, sign one word, and SigLa reads it for you.
                  </Text>
                  <Button
                    title="Open camera"
                    onPress={() => onNavigate('Translator')}
                    variant="primary"
                    isDarkMode={isDarkMode}
                    className="mt-4 self-start"
                  />
                </View>
                <View
                  className={`w-20 h-20 rounded-2xl items-center justify-center ${
                    isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                  }`}
                >
                  <IconHand
                    size={40}
                    color={isDarkMode ? '#C9D8FA' : '#13306B'}
                  />
                </View>
              </View>
            </Card>
          </View>

          {/* Stats Bar */}
          <View className="mt-4">
            <StatCard
              wordsCount={wordsCount}
              translatedTodayCount={translatedToday}
              favoritesCount={favoritesCount}
              isDarkMode={isDarkMode}
            />
          </View>

          {/* Categories Section */}
          <View className="mt-6">
            <View className="flex-row items-center justify-between mb-3">
              <Text
                className={`text-base font-bold ${
                  isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                }`}
              >
                Categories
              </Text>
              <TouchableOpacity
                onPress={() => onNavigate('WordBank')}
                className="py-1 px-1"
              >
                <Text
                  className={`text-xs font-semibold ${
                    isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                  }`}
                >
                  View all
                </Text>
              </TouchableOpacity>
            </View>

            <View className="flex-row flex-wrap justify-between">
              {categories.map((cat, idx) => (
                <View key={cat.name} className="w-[48%] mb-3">
                  <CategoryCard
                    name={cat.name}
                    wordCount={cat.wordCount}
                    isHighlight={idx === 0}
                    onPress={() =>
                      onNavigate('CategoryWordList', { categoryName: cat.name })
                    }
                    isDarkMode={isDarkMode}
                  />
                </View>
              ))}
              {categories.length === 0 && (
                <Card variant="tint" isDarkMode={isDarkMode} className="w-full p-4">
                  <Text
                    className={`text-xs ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    Connect to the internet once to download the word bank.
                  </Text>
                </Card>
              )}
            </View>
          </View>

          {/* Recent Translations Section */}
          <View className="mt-4">
            <View className="flex-row items-center justify-between mb-2">
              <Text
                className={`text-base font-bold ${
                  isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                }`}
              >
                Recent translations
              </Text>
              {recentList.length > 0 && (
                <TouchableOpacity
                  onPress={() => onNavigate('History')}
                  className="py-1 px-1"
                >
                  <Text
                    className={`text-xs font-semibold ${
                      isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                    }`}
                  >
                    View all
                  </Text>
                </TouchableOpacity>
              )}
            </View>

            {recentList.length > 0 ? (
              <View>
                {recentList.map((entry, idx) => (
                  <RecentItem
                    key={`${entry.timestamp}-${idx}`}
                    word={entry.word}
                    timeString={`${Storage.formatDate(entry.timestamp)} · ${Storage.formatTime(entry.timestamp)}`}
                    onPress={() => onNavigate('History')}
                    isDarkMode={isDarkMode}
                  />
                ))}
              </View>
            ) : (
              <Card variant="tint" isDarkMode={isDarkMode} className="p-4 mt-1">
                <Text
                  className={`text-xs mb-3 ${
                    isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                  }`}
                >
                  Your translations will appear here.
                </Text>
                <Button
                  title="Start translating"
                  onPress={() => onNavigate('Translator')}
                  variant="primary"
                  isDarkMode={isDarkMode}
                  className="self-start"
                />
              </Card>
            )}
          </View>
        </View>
      </ScrollView>

      {/* Persistent Bottom Nav */}
      <BottomNavBar
        currentTab="home"
        onSelectTab={(tab) => {
          if (tab === 'home') return;
          if (tab === 'word_bank') onNavigate('WordBank');
          if (tab === 'translator') onNavigate('Translator');
          if (tab === 'history') onNavigate('History');
          if (tab === 'settings') onNavigate('Settings');
        }}
        isDarkMode={isDarkMode}
      />
    </SafeAreaView>
  );
};
