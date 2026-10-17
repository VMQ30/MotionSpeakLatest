import React from 'react';
import { View, Text } from 'react-native';
import { Modal } from './Modal';
import { Button } from './Button';

interface DownloadAllModalProps {
  visible: boolean;
  completed: number;
  total: number;
  currentWord?: string;
  onCancel: () => void;
  isDarkMode?: boolean;
}

export const DownloadAllModal: React.FC<DownloadAllModalProps> = ({
  visible,
  completed,
  total,
  currentWord = '',
  onCancel,
  isDarkMode = false,
}) => {
  const percent = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;

  return (
    <Modal visible={visible} onClose={onCancel} dismissible={false}>
      <View
        className={`rounded-3xl overflow-hidden shadow-2xl ${
          isDarkMode ? 'bg-[#0C1430]' : 'bg-white'
        }`}
      >
        {/* Navy Header */}
        <View className="bg-[#13306B] px-5 pt-5 pb-4">
          <Text className="text-white text-base font-bold">
            Downloading demo videos
          </Text>
        </View>

        {/* Dialog Body */}
        <View className="p-5">
          <Text
            className={`text-sm font-bold ${
              isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
            }`}
          >
            {total > 0 ? `${completed} of ${total} downloaded` : 'Preparing…'}
          </Text>

          {/* Progress Bar */}
          <View
            className={`w-full h-2 rounded-full mt-3 overflow-hidden ${
              isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
            }`}
          >
            <View
              className="h-full bg-[#13306B] rounded-full"
              style={{ width: `${percent}%` }}
            />
          </View>

          {currentWord ? (
            <Text
              className={`text-xs mt-2 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
              numberOfLines={1}
            >
              Downloading: {currentWord}
            </Text>
          ) : null}

          <View className="flex-row items-center justify-end mt-5">
            <Button
              title="Cancel"
              variant="chip"
              onPress={onCancel}
              isDarkMode={isDarkMode}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
};
