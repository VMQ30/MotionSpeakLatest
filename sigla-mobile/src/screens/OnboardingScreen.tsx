import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  Dimensions,
  StyleSheet,
  SafeAreaView,
} from 'react-native';
import { Storage } from '../services/storage';
import { Button } from '../components/Button';
import { IconHand, IconBook, IconHome } from '../components/Icons';

const { width } = Dimensions.get('window');

interface OnboardingScreenProps {
  onComplete: () => void;
  isDarkMode?: boolean;
}

export const OnboardingScreen: React.FC<OnboardingScreenProps> = ({
  onComplete,
  isDarkMode = false,
}) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [userName, setUserName] = useState('');

  const steps = [
    {
      title: 'Translate FSL in real time',
      subtitle:
        'Point your camera at Filipino Sign Language gestures and SigLa reads them aloud and displays them in English and Filipino.',
      icon: <IconHand size={64} color="#13306B" />,
    },
    {
      title: 'Learn with the Word Bank',
      subtitle:
        'Browse signs by category, watch slow-motion demonstration videos, and save your favorite signs for quick access.',
      icon: <IconBook size={64} color="#13306B" />,
    },
    {
      title: 'Works completely offline',
      subtitle:
        'All camera translation runs fully on your device without needing mobile data or Wi-Fi. Your data stays private.',
      icon: <IconHome size={64} color="#13306B" />,
    },
    {
      title: 'What should we call you?',
      subtitle:
        'Your first name is optional and only used for your dashboard greeting. It never leaves your phone.',
      isNameStep: true,
    },
  ];

  const handleNext = () => {
    if (currentStep < steps.length - 1) {
      setCurrentStep(currentStep + 1);
    } else {
      finishOnboarding();
    }
  };

  const finishOnboarding = () => {
    Storage.updateSettings({
      isOnboardingDone: true,
      userName: userName.trim() || null,
    });
    onComplete();
  };

  const step = steps[currentStep];

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <View className="flex-1 px-6 pt-4 pb-6 justify-between">
        {/* Top Header: Brand mark + Step pill */}
        <View className="flex-row items-center justify-between">
          <Text
            className={`text-base font-bold tracking-widest ${
              isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
            }`}
          >
            SIGLA
          </Text>
          <View
            className={`px-3 py-1 rounded-full ${
              isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
            }`}
          >
            <Text
              className={`text-xs font-semibold ${
                isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
              }`}
            >
              {currentStep + 1} of {steps.length}
            </Text>
          </View>
        </View>

        {/* Center Content */}
        <View className="items-center my-auto">
          {!step.isNameStep ? (
            <View
              className={`w-36 h-36 rounded-3xl items-center justify-center mb-10 ${
                isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
            >
              {step.icon}
            </View>
          ) : (
            <View className="w-full mb-8">
              <View
                className={`w-20 h-20 rounded-full items-center justify-center mx-auto mb-6 ${
                  isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                }`}
              >
                <Text
                  className={`text-2xl font-bold ${
                    isDarkMode ? 'text-[#13306B]' : 'text-white'
                  }`}
                >
                  {userName.trim() ? userName.trim().charAt(0).toUpperCase() : '?'}
                </Text>
              </View>

              <View
                className={`border rounded-2xl px-4 py-3 ${
                  isDarkMode
                    ? 'border-[#233A73] bg-[#111B3E]'
                    : 'border-[#13306B] bg-[#EEF3FC]'
                }`}
              >
                <TextInput
                  value={userName}
                  onChangeText={(t) => {
                    if (t.length <= 40) setUserName(t);
                  }}
                  placeholder="Your first name (optional)"
                  placeholderTextColor={isDarkMode ? '#A9B6D6' : '#626B7E'}
                  className={`text-base ${
                    isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                  }`}
                />
              </View>
            </View>
          )}

          <Text
            className={`text-2xl font-bold text-center px-2 ${
              isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
            }`}
          >
            {step.title}
          </Text>

          <Text
            className={`text-sm text-center mt-3 leading-6 px-4 ${
              isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
            }`}
          >
            {step.subtitle}
          </Text>
        </View>

        {/* Bottom Navigation */}
        <View>
          {/* Indicator Dots */}
          <View className="flex-row items-center justify-center mb-6">
            {steps.map((_, index) => {
              const isActive = index === currentStep;
              return (
                <View
                  key={index}
                  className={`h-2 rounded-full mx-1 ${
                    isActive
                      ? `w-6 ${isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'}`
                      : `w-2 ${isDarkMode ? 'bg-[#233A73]' : 'bg-[#D7DBE4]'}`
                  }`}
                />
              );
            })}
          </View>

          {/* Buttons */}
          <View className="flex-row items-center justify-between">
            {currentStep < steps.length - 1 ? (
              <TouchableOpacity onPress={finishOnboarding} className="py-3 px-2">
                <Text
                  className={`font-semibold text-sm ${
                    isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                  }`}
                >
                  Skip
                </Text>
              </TouchableOpacity>
            ) : (
              <View className="w-16" />
            )}

            <Button
              title={currentStep === steps.length - 1 ? 'Get Started' : 'Next'}
              onPress={handleNext}
              variant="primary"
              isDarkMode={isDarkMode}
            />
          </View>
        </View>
      </View>
    </SafeAreaView>
  );
};
