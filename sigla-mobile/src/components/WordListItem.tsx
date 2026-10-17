import React from 'react';
import { View, Text, TouchableOpacity, Image } from 'react-native';
import { WordBankWord } from '../types';
import { IconHeart, IconBook } from './Icons';

interface WordListItemProps {
  word: WordBankWord;
  isFavorite: boolean;
  onPress: () => void;
  onToggleFavorite: () => void;
  isDarkMode?: boolean;
}

export const WordListItem: React.FC<WordListItemProps> = ({
  word,
  isFavorite,
  onPress,
  onToggleFavorite,
  isDarkMode = false,
}) => {
  return (
    <TouchableOpacity
      onPress={onPress}
      activeOpacity={0.8}
      className={`flex-row items-center p-3 mb-3 rounded-2xl min-h-[76px] ${
        isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
      }`}
    >
      <View
        className={`w-[52px] h-[52px] rounded-xl items-center justify-center overflow-hidden ${
          isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
        }`}
      >
        {word.thumbnail_url ? (
          <Image
            source={{ uri: word.thumbnail_url }}
            className="w-full h-full"
            resizeMode="cover"
          />
        ) : (
          <IconBook size={24} color={isDarkMode ? '#B8CCF8' : '#13306B'} />
        )}
      </View>

      <View className="flex-1 ml-3.5">
        <Text
          className={`font-bold text-sm ${
            isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
          }`}
          numberOfLines={1}
        >
          {word.label}
        </Text>
        {word.filipino_translation ? (
          <Text
            className={`text-xs mt-0.5 ${
              isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
            }`}
            numberOfLines={1}
          >
            {word.filipino_translation}
          </Text>
        ) : null}
      </View>

      <TouchableOpacity
        onPress={(e) => {
          e.stopPropagation?.();
          onToggleFavorite();
        }}
        className="p-2 ml-2"
        hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
      >
        <IconHeart
          size={20}
          filled={isFavorite}
          color={isFavorite ? '#E53935' : isDarkMode ? '#A9B6D6' : '#626B7E'}
        />
      </TouchableOpacity>
    </TouchableOpacity>
  );
};
