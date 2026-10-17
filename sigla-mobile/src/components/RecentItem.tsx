import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { IconChat } from './Icons';

interface RecentItemProps {
  word: string;
  timeString: string;
  onPress: () => void;
  isDarkMode?: boolean;
}

export const RecentItem: React.FC<RecentItemProps> = ({
  word,
  timeString,
  onPress,
  isDarkMode = false,
}) => {
  return (
    <TouchableOpacity
      onPress={onPress}
      activeOpacity={0.7}
      className="flex-row items-center py-2.5"
    >
      <View
        className={`w-10 h-10 rounded-full items-center justify-center ${
          isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
        }`}
      >
        <IconChat size={18} color={isDarkMode ? '#B8CCF8' : '#13306B'} />
      </View>
      <View className="ml-3 flex-1">
        <Text
          className={`font-semibold text-sm ${
            isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
          }`}
          numberOfLines={1}
        >
          {word}
        </Text>
        <Text
          className={`text-xs mt-0.5 ${
            isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
          }`}
        >
          {timeString}
        </Text>
      </View>
    </TouchableOpacity>
  );
};
