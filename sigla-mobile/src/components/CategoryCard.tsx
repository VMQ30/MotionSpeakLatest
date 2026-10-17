import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { IconTag } from './Icons';

interface CategoryCardProps {
  name: string;
  wordCount: number;
  isHighlight?: boolean;
  onPress: () => void;
  isDarkMode?: boolean;
}

export const CategoryCard: React.FC<CategoryCardProps> = ({
  name,
  wordCount,
  isHighlight = false,
  onPress,
  isDarkMode = false,
}) => {
  const getBgColor = () => {
    if (isHighlight) {
      return isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]';
    }
    return isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]';
  };

  const getTextColor = () => {
    if (isHighlight) {
      return isDarkMode ? 'text-[#13306B]' : 'text-white';
    }
    return isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]';
  };

  const getSubtextColor = () => {
    if (isHighlight) {
      return isDarkMode ? 'text-[#13306B]/80' : 'text-white/80';
    }
    return isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]';
  };

  const getIconColor = () => {
    if (isHighlight) {
      return isDarkMode ? '#13306B' : '#FFFFFF';
    }
    return isDarkMode ? '#B8CCF8' : '#13306B';
  };

  return (
    <TouchableOpacity
      onPress={onPress}
      activeOpacity={0.85}
      className={`p-3.5 rounded-2xl ${getBgColor()}`}
    >
      <View className="mb-2">
        <IconTag size={22} color={getIconColor()} />
      </View>
      <Text className={`font-bold text-sm ${getTextColor()}`} numberOfLines={1}>
        {name}
      </Text>
      <Text className={`text-xs mt-0.5 ${getSubtextColor()}`}>
        {wordCount} {wordCount === 1 ? 'word' : 'words'}
      </Text>
    </TouchableOpacity>
  );
};
