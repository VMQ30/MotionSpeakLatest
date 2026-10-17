import React, { useState, useEffect } from 'react';
import { View, Text, TextInput } from 'react-native';
import { Modal } from './Modal';
import { Button } from './Button';

interface EditNameModalProps {
  visible: boolean;
  initialValue?: string | null;
  onSave: (name: string) => void;
  onCancel: () => void;
  isDarkMode?: boolean;
}

export const EditNameModal: React.FC<EditNameModalProps> = ({
  visible,
  initialValue = '',
  onSave,
  onCancel,
  isDarkMode = false,
}) => {
  const [name, setName] = useState(initialValue || '');

  useEffect(() => {
    if (visible) {
      setName(initialValue || '');
    }
  }, [visible, initialValue]);

  return (
    <Modal visible={visible} onClose={onCancel}>
      <View
        className={`rounded-3xl p-5 shadow-2xl ${
          isDarkMode ? 'bg-[#0C1430]' : 'bg-white'
        }`}
      >
        <Text
          className={`text-base font-bold mb-3 ${
            isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
          }`}
        >
          What should we call you?
        </Text>

        <View
          className={`border rounded-2xl px-3 py-2.5 mb-1 ${
            isDarkMode
              ? 'border-[#233A73] bg-[#111B3E]'
              : 'border-[#13306B] bg-[#EEF3FC]'
          }`}
        >
          <TextInput
            value={name}
            onChangeText={(t) => {
              if (t.length <= 40) setName(t);
            }}
            placeholder="Your first name"
            placeholderTextColor={isDarkMode ? '#A9B6D6' : '#626B7E'}
            className={`text-sm ${isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'}`}
            autoFocus
          />
        </View>

        <Text
          className={`text-[10px] text-right mb-4 ${
            isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
          }`}
        >
          {name.length}/40
        </Text>

        <View className="flex-row items-center justify-end space-x-2">
          <Button
            title="Cancel"
            variant="chip"
            onPress={onCancel}
            isDarkMode={isDarkMode}
            className="mr-2"
          />
          <Button
            title="Save"
            variant="primary"
            onPress={() => onSave(name.trim())}
            isDarkMode={isDarkMode}
          />
        </View>
      </View>
    </Modal>
  );
};
