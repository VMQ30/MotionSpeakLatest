import React from 'react';
import { TouchableOpacity, Text, View, StyleSheet, ActivityIndicator } from 'react-native';

interface ButtonProps {
  title?: string;
  onPress?: () => void;
  variant?: 'primary' | 'secondary' | 'chip' | 'danger' | 'ghost';
  active?: boolean;
  disabled?: boolean;
  loading?: boolean;
  icon?: React.ReactNode;
  className?: string;
  isDarkMode?: boolean;
}

export const Button: React.FC<ButtonProps> = ({
  title,
  onPress,
  variant = 'primary',
  active = false,
  disabled = false,
  loading = false,
  icon,
  className = '',
  isDarkMode = false,
}) => {
  const getVariantStyles = () => {
    switch (variant) {
      case 'primary':
        return isDarkMode
          ? 'bg-[#C9D8FA] active:bg-[#B8CCF8]'
          : 'bg-[#13306B] active:bg-[#0E2350]';
      case 'secondary':
        return isDarkMode
          ? 'bg-[#172350] border border-[#233A73]'
          : 'bg-[#EEF3FC] border border-[#D5E2F8]';
      case 'chip':
        if (active) {
          return isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]';
        }
        return isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]';
      case 'danger':
        return 'bg-[#E53935] active:bg-[#C62828]';
      case 'ghost':
        return 'bg-transparent';
      default:
        return 'bg-[#13306B]';
    }
  };

  const getTextStyles = () => {
    switch (variant) {
      case 'primary':
        return isDarkMode ? 'text-[#13306B]' : 'text-white';
      case 'secondary':
        return isDarkMode ? 'text-[#C9D8FA]' : 'text-[#13306B]';
      case 'chip':
        if (active) {
          return isDarkMode ? 'text-[#13306B]' : 'text-white';
        }
        return isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]';
      case 'danger':
        return 'text-white';
      case 'ghost':
        return isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]';
      default:
        return 'text-white';
    }
  };

  const getContainerPadding = () => {
    if (variant === 'chip') {
      return 'px-4 py-2 rounded-full min-h-[36px]';
    }
    return 'px-5 py-3 rounded-2xl min-h-[48px]';
  };

  return (
    <TouchableOpacity
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.8}
      className={`flex-row items-center justify-center ${getContainerPadding()} ${getVariantStyles()} ${
        disabled ? 'opacity-50' : ''
      } ${className}`}
    >
      {loading ? (
        <ActivityIndicator
          size="small"
          color={variant === 'primary' && !isDarkMode ? '#FFFFFF' : '#13306B'}
        />
      ) : (
        <>
          {icon ? <View className={title ? 'mr-2' : ''}>{icon}</View> : null}
          {title ? (
            <Text
              className={`font-semibold text-center text-sm ${getTextStyles()}`}
              style={{ letterSpacing: 0.2 }}
            >
              {title}
            </Text>
          ) : null}
        </>
      )}
    </TouchableOpacity>
  );
};
