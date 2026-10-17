import React from 'react';
import { View, TextInput, TouchableOpacity, Text } from 'react-native';
import { IconSearch, IconClose } from './Icons';

interface SearchBarProps {
  value?: string;
  onChangeText?: (text: string) => void;
  placeholder?: string;
  onPress?: () => void;
  readOnly?: boolean;
  onClear?: () => void;
  isDarkMode?: boolean;
}

export const SearchBar: React.FC<SearchBarProps> = ({
  value = '',
  onChangeText,
  placeholder = 'Search FSL signs',
  onPress,
  readOnly = false,
  onClear,
  isDarkMode = false,
}) => {
  const content = (
    <View
      className={`flex-row items-center px-4 h-12 rounded-2xl ${
        isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
      }`}
    >
      <IconSearch size={18} color={isDarkMode ? '#A9B6D6' : '#626B7E'} />
      {readOnly ? (
        <Text
          className={`flex-1 ml-3 text-sm ${
            isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
          }`}
        >
          {placeholder}
        </Text>
      ) : (
        <TextInput
          value={value}
          onChangeText={onChangeText}
          placeholder={placeholder}
          placeholderTextColor={isDarkMode ? '#A9B6D6' : '#626B7E'}
          className={`flex-1 ml-3 text-sm py-0 ${
            isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
          }`}
          autoCorrect={false}
          autoCapitalize="none"
        />
      )}
      {!readOnly && value.length > 0 && onClear && (
        <TouchableOpacity onPress={onClear} className="p-1">
          <IconClose size={16} color={isDarkMode ? '#A9B6D6' : '#626B7E'} />
        </TouchableOpacity>
      )}
    </View>
  );

  if (readOnly && onPress) {
    return (
      <TouchableOpacity onPress={onPress} activeOpacity={0.8}>
        {content}
      </TouchableOpacity>
    );
  }

  return content;
};
