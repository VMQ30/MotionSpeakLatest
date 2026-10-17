import React from 'react';
import { View, Text } from 'react-native';
import { Modal } from './Modal';
import { Button } from './Button';

interface ConfirmDialogProps {
  visible: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  variant?: 'danger' | 'primary';
  isDarkMode?: boolean;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  visible,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  variant = 'danger',
  isDarkMode = false,
}) => {
  return (
    <Modal visible={visible} onClose={onCancel}>
      <View
        className={`rounded-3xl overflow-hidden shadow-2xl ${
          isDarkMode ? 'bg-[#0C1430]' : 'bg-white'
        }`}
      >
        {/* Navy Header */}
        <View className="bg-[#13306B] px-5 pt-5 pb-4">
          <Text className="text-white text-base font-bold">{title}</Text>
        </View>

        {/* Dialog Body */}
        <View className="p-5">
          <Text
            className={`text-sm font-semibold mb-6 leading-5 ${
              isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
            }`}
          >
            {message}
          </Text>

          <View className="flex-row items-center justify-end space-x-2">
            <Button
              title={cancelLabel}
              variant="chip"
              onPress={onCancel}
              isDarkMode={isDarkMode}
              className="mr-2"
            />
            <Button
              title={confirmLabel}
              variant={variant === 'danger' ? 'danger' : 'primary'}
              onPress={onConfirm}
              isDarkMode={isDarkMode}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
};
