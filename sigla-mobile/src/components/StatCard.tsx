import React from 'react';
import { View, Text } from 'react-native';
import { Card } from './Card';

interface StatCardProps {
  wordsCount: number;
  translatedTodayCount: number;
  favoritesCount: number;
  isDarkMode?: boolean;
}

export const StatCard: React.FC<StatCardProps> = ({
  wordsCount,
  translatedTodayCount,
  favoritesCount,
  isDarkMode = false,
}) => {
  return (
    <Card variant="tint" isDarkMode={isDarkMode} className="p-4">
      <View className="flex-row items-center justify-between">
        <View className="flex-1 items-center">
          <Text
            className={`text-2xl font-bold ${
              isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
            }`}
          >
            {wordsCount}
          </Text>
          <Text
            className={`text-xs mt-1 ${
              isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
            }`}
          >
            Words
          </Text>
        </View>

        <View
          className={`w-[1px] h-8 ${
            isDarkMode ? 'bg-white/10' : 'bg-[#13306B]/10'
          }`}
        />

        <View className="flex-1 items-center">
          <Text
            className={`text-2xl font-bold ${
              isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
            }`}
          >
            {translatedTodayCount}
          </Text>
          <Text
            className={`text-xs mt-1 text-center ${
              isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
            }`}
          >
            Translated today
          </Text>
        </View>

        <View
          className={`w-[1px] h-8 ${
            isDarkMode ? 'bg-white/10' : 'bg-[#13306B]/10'
          }`}
        />

        <View className="flex-1 items-center">
          <Text
            className={`text-2xl font-bold ${
              isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
            }`}
          >
            {favoritesCount}
          </Text>
          <Text
            className={`text-xs mt-1 ${
              isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
            }`}
          >
            Favorites
          </Text>
        </View>
      </View>
    </Card>
  );
};
