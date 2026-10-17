import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Platform } from 'react-native';
import { TabType } from '../types';
import { IconHome, IconBook, IconHand, IconHistory, IconSettings } from './Icons';

interface BottomNavBarProps {
  currentTab: TabType;
  onSelectTab: (tab: TabType) => void;
  isDarkMode?: boolean;
}

export const BottomNavBar: React.FC<BottomNavBarProps> = ({
  currentTab,
  onSelectTab,
  isDarkMode = false,
}) => {
  const getTabColor = (tab: TabType) => {
    const isActive = currentTab === tab;
    if (isActive) {
      return isDarkMode ? '#C9D8FA' : '#13306B';
    }
    return isDarkMode ? '#62739D' : '#94A3B8';
  };

  return (
    <View className="absolute bottom-0 left-0 right-0 items-center">
      {/* Raised Floating Translate Button */}
      <View className="absolute -top-6 z-20 items-center justify-center">
        <TouchableOpacity
          onPress={() => onSelectTab('translator')}
          activeOpacity={0.85}
          className={`w-[60px] h-[60px] rounded-full items-center justify-center shadow-lg ${
            isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
          }`}
          style={{
            elevation: 8,
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.25,
            shadowRadius: 6,
          }}
        >
          <IconHand size={28} color={isDarkMode ? '#13306B' : '#FFFFFF'} />
        </TouchableOpacity>
      </View>

      {/* Main Bottom Bar */}
      <View
        className={`w-full h-16 flex-row items-center justify-around px-2 border-t ${
          isDarkMode
            ? 'bg-[#111B3E] border-[#233A73]'
            : 'bg-white border-[#E2E8F4]'
        }`}
        style={{
          elevation: 6,
          shadowColor: '#000',
          shadowOffset: { width: 0, height: -2 },
          shadowOpacity: 0.06,
          shadowRadius: 4,
        }}
      >
        {/* Tab 1: Home */}
        <TouchableOpacity
          onPress={() => onSelectTab('home')}
          className="flex-1 items-center justify-center py-1"
          activeOpacity={0.7}
        >
          <IconHome size={22} color={getTabColor('home')} />
          <Text
            className="text-[11px] font-semibold mt-1"
            style={{ color: getTabColor('home') }}
          >
            Home
          </Text>
        </TouchableOpacity>

        {/* Tab 2: Word Bank */}
        <TouchableOpacity
          onPress={() => onSelectTab('word_bank')}
          className="flex-1 items-center justify-center py-1 mr-6"
          activeOpacity={0.7}
        >
          <IconBook size={22} color={getTabColor('word_bank')} />
          <Text
            className="text-[11px] font-semibold mt-1"
            style={{ color: getTabColor('word_bank') }}
          >
            Word Bank
          </Text>
        </TouchableOpacity>

        {/* Space for Center FAB */}
        <View className="w-12" />

        {/* Tab 3: History */}
        <TouchableOpacity
          onPress={() => onSelectTab('history')}
          className="flex-1 items-center justify-center py-1 ml-6"
          activeOpacity={0.7}
        >
          <IconHistory size={22} color={getTabColor('history')} />
          <Text
            className="text-[11px] font-semibold mt-1"
            style={{ color: getTabColor('history') }}
          >
            History
          </Text>
        </TouchableOpacity>

        {/* Tab 4: Settings */}
        <TouchableOpacity
          onPress={() => onSelectTab('settings')}
          className="flex-1 items-center justify-center py-1"
          activeOpacity={0.7}
        >
          <IconSettings size={22} color={getTabColor('settings')} />
          <Text
            className="text-[11px] font-semibold mt-1"
            style={{ color: getTabColor('settings') }}
          >
            Settings
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};
