import React from 'react';
import { View, TouchableOpacity, StyleSheet, ViewStyle } from 'react-native';

interface CardProps {
  children: React.ReactNode;
  variant?: 'tint' | 'brand' | 'outlined';
  onPress?: () => void;
  className?: string;
  isDarkMode?: boolean;
  style?: ViewStyle;
}

export const Card: React.FC<CardProps> = ({
  children,
  variant = 'tint',
  onPress,
  className = '',
  isDarkMode = false,
  style,
}) => {
  const getVariantStyles = () => {
    switch (variant) {
      case 'tint':
        return isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]';
      case 'brand':
        return isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]';
      case 'outlined':
        return isDarkMode
          ? 'bg-[#111B3E] border border-[#233A73]'
          : 'bg-white border border-[#E2E8F4]';
      default:
        return isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]';
    }
  };

  const content = (
    <View
      className={`rounded-3xl p-4 ${getVariantStyles()} ${className}`}
      style={style}
    >
      {children}
    </View>
  );

  if (onPress) {
    return (
      <TouchableOpacity onPress={onPress} activeOpacity={0.85}>
        {content}
      </TouchableOpacity>
    );
  }

  return content;
};
