import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  SectionList,
  SafeAreaView,
  TouchableOpacity,
} from 'react-native';
import { Header } from '../components/Header';
import { Button } from '../components/Button';
import { BottomNavBar } from '../components/BottomNavBar';
import { ConfirmDialog } from '../components/ConfirmDialog';
import {
  IconChat,
  IconSpeaker,
  IconTrash,
} from '../components/Icons';
import { Storage } from '../services/storage';
import { NativeBridge } from '../services/nativeModules';
import { TranslationEntry, HistoryGroup } from '../types';

interface TranslationHistoryScreenProps {
  onNavigate: (screen: string, params?: any) => void;
  isDarkMode?: boolean;
}

export const TranslationHistoryScreen: React.FC<TranslationHistoryScreenProps> = ({
  onNavigate,
  isDarkMode = false,
}) => {
  const [groupedHistory, setGroupedHistory] = useState<HistoryGroup[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [showClearConfirm, setShowClearConfirm] = useState(false);

  const loadHistory = useCallback(() => {
    const grouped = Storage.getGroupedHistory();
    const all = Storage.getHistory();
    setGroupedHistory(grouped);
    setTotalCount(all.length);
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const handleSpeak = (word: string) => {
    const settings = Storage.getSettings();
    NativeBridge.speak(word, settings.volume, settings.voiceType);
  };

  const handleDeleteItem = (timestamp: number) => {
    const all = Storage.getHistory();
    const idx = all.findIndex((e) => e.timestamp === timestamp);
    if (idx !== -1) {
      Storage.deleteHistoryAt(idx);
      loadHistory();
    }
  };

  const handleClearAll = () => {
    Storage.clearHistory();
    setShowClearConfirm(false);
    loadHistory();
  };

  const sections = groupedHistory.map((g) => ({
    title: g.date,
    data: g.entries,
  }));

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <View className="flex-1 px-5 pt-3">
        <Header
          title="History"
          subtitle={`${totalCount} ${totalCount === 1 ? 'translation' : 'translations'}`}
          rightAction={
            totalCount > 0 ? (
              <Button
                title="Clear all"
                variant="chip"
                onPress={() => setShowClearConfirm(true)}
                isDarkMode={isDarkMode}
              />
            ) : null
          }
          isDarkMode={isDarkMode}
        />

        <SectionList
          sections={sections}
          keyExtractor={(item, index) => `${item.timestamp}-${index}`}
          contentContainerStyle={{ paddingTop: 10, paddingBottom: 110 }}
          renderSectionHeader={({ section: { title } }) => (
            <View className="pt-4 pb-2">
              <Text
                className={`text-xs font-bold uppercase tracking-wider ${
                  isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                }`}
              >
                {title}
              </Text>
            </View>
          )}
          renderItem={({ item }) => (
            <View
              className={`flex-row items-center justify-between p-3.5 mb-2.5 rounded-2xl ${
                isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
            >
              <View className="flex-row items-center flex-1 mr-2">
                <View
                  className={`w-10 h-10 rounded-full items-center justify-center mr-3 ${
                    isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                  }`}
                >
                  <IconChat
                    size={18}
                    color={isDarkMode ? '#B8CCF8' : '#13306B'}
                  />
                </View>

                <View className="flex-1">
                  <View className="flex-row items-center">
                    <Text
                      className={`font-bold text-sm mr-2 ${
                        isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                      }`}
                      numberOfLines={1}
                    >
                      {item.word}
                    </Text>
                    {item.confidence > 0 && (
                      <View className="bg-green-600/10 dark:bg-green-400/20 px-2 py-0.5 rounded-full">
                        <Text className="text-[10px] font-bold text-green-700 dark:text-green-300">
                          {item.confidence}%
                        </Text>
                      </View>
                    )}
                  </View>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    {Storage.formatTime(item.timestamp)} · {item.gestureType || 'Tap'}
                  </Text>
                </View>
              </View>

              <View className="flex-row items-center space-x-1">
                <TouchableOpacity
                  onPress={() => handleSpeak(item.word)}
                  className="p-2"
                  hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                >
                  <IconSpeaker
                    size={20}
                    color={isDarkMode ? '#C9D8FA' : '#13306B'}
                  />
                </TouchableOpacity>

                <TouchableOpacity
                  onPress={() => handleDeleteItem(item.timestamp)}
                  className="p-2"
                  hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                >
                  <IconTrash size={18} color="#E53935" />
                </TouchableOpacity>
              </View>
            </View>
          )}
          ListEmptyComponent={
            <View className="items-center justify-center py-20">
              <View
                className={`w-16 h-16 rounded-full items-center justify-center mb-4 ${
                  isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                }`}
              >
                <IconChat
                  size={28}
                  color={isDarkMode ? '#B8CCF8' : '#13306B'}
                />
              </View>
              <Text
                className={`text-base font-bold ${
                  isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                }`}
              >
                No translations yet
              </Text>
              <Text
                className={`text-xs text-center mt-1 mb-5 px-8 ${
                  isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                }`}
              >
                Start translating Filipino Sign Language with the camera and your history will be recorded here.
              </Text>
              <Button
                title="Start translating"
                variant="primary"
                onPress={() => onNavigate('Translator')}
                isDarkMode={isDarkMode}
              />
            </View>
          }
        />
      </View>

      {/* Clear All Confirmation Dialog */}
      <ConfirmDialog
        visible={showClearConfirm}
        title="Delete all history?"
        message="This action will delete all recorded translations from your device and cannot be undone."
        confirmLabel="Delete"
        variant="danger"
        onConfirm={handleClearAll}
        onCancel={() => setShowClearConfirm(false)}
        isDarkMode={isDarkMode}
      />

      {/* Persistent Bottom Nav */}
      <BottomNavBar
        currentTab="history"
        onSelectTab={(tab) => {
          if (tab === 'history') return;
          if (tab === 'home') onNavigate('Home');
          if (tab === 'word_bank') onNavigate('WordBank');
          if (tab === 'translator') onNavigate('Translator');
          if (tab === 'settings') onNavigate('Settings');
        }}
        isDarkMode={isDarkMode}
      />
    </SafeAreaView>
  );
};
