import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import { IconBack } from './Icons';

interface HeaderProps {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  rightAction?: React.ReactNode;
  isDarkMode?: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  title,
  subtitle,
  onBack,
  rightAction,
  isDarkMode = false,
}) => {
  return (
    <View className="flex-row items-center justify-between pb-3 pt-2">
      <View className="flex-row items-center flex-1">
        {onBack && (
          <TouchableOpacity
            onPress={onBack}
            className={`w-10 h-10 rounded-full items-center justify-center mr-3 ${
              isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
            }`}
            activeOpacity={0.7}
          >
            <IconBack size={20} color={isDarkMode ? '#C9D8FA' : '#13306B'} />
          </TouchableOpacity>
        )}
        <View className="flex-1">
          <Text
            className={`text-xl font-bold ${
              isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
            }`}
          >
            {title}
          </Text>
          {subtitle && (
            <Text
              className={`text-xs mt-0.5 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              {subtitle}
            </Text>
          )}
        </View>
      </View>
      {rightAction && <View className="ml-2">{rightAction}</View>}
    </View>
  );
};
