import React from 'react';
import {
  Modal as RNModal,
  View,
  TouchableWithoutFeedback,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';

interface ModalProps {
  visible: boolean;
  onClose: () => void;
  children: React.ReactNode;
  dismissible?: boolean;
}

export const Modal: React.FC<ModalProps> = ({
  visible,
  onClose,
  children,
  dismissible = true,
}) => {
  return (
    <RNModal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={dismissible ? onClose : undefined}
    >
      <TouchableWithoutFeedback onPress={dismissible ? onClose : undefined}>
        <View className="flex-1 bg-black/60 items-center justify-center p-4">
          <KeyboardAvoidingView
            behavior={Platform.OS === 'ios' ? 'padding' : undefined}
            className="w-full items-center justify-center"
          >
            <TouchableWithoutFeedback>
              <View className="w-full max-w-[340px]">{children}</View>
            </TouchableWithoutFeedback>
          </KeyboardAvoidingView>
        </View>
      </TouchableWithoutFeedback>
    </RNModal>
  );
};
