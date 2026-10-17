import React, { useEffect } from 'react';
import { View, Text, Image, StyleSheet } from 'react-native';
import { Storage } from '../services/storage';
import { Api } from '../services/api';
import { NativeBridge } from '../services/nativeModules';

interface SplashScreenProps {
  onFinish: (nextScreen: 'Onboarding' | 'Home') => void;
  isDarkMode?: boolean;
}

export const SplashScreen: React.FC<SplashScreenProps> = ({ onFinish, isDarkMode = false }) => {
  useEffect(() => {
    let isMounted = true;

    const prepare = async () => {
      await Storage.initFromNative();
      // Start background preload of word bank & categories
      Api.getWordBank();
      Api.getCategories();

      // Short delay for launch branding
      setTimeout(() => {
        if (!isMounted) return;
        const settings = Storage.getSettings();
        if (!settings.isOnboardingDone) {
          onFinish('Onboarding');
        } else {
          onFinish('Home');
        }
      }, 1200);
    };

    prepare();

    return () => {
      isMounted = false;
    };
  }, [onFinish]);

  return (
    <View
      className={`flex-1 items-center justify-center p-6 ${
        isDarkMode ? 'bg-[#0C1430]' : 'bg-white'
      }`}
    >
      <View
        className="w-28 h-28 rounded-3xl bg-[#1A387E] items-center justify-center mb-6 shadow-xl"
        style={{
          elevation: 10,
          shadowColor: '#13306B',
          shadowOffset: { width: 0, height: 6 },
          shadowOpacity: 0.3,
          shadowRadius: 10,
        }}
      >
        <Image
          source={require('../../SigLa-Logo.png')}
          className="w-20 h-20"
          resizeMode="contain"
        />
      </View>

      <Text
        className={`text-3xl font-extrabold tracking-wider ${
          isDarkMode ? 'text-[#C9D8FA]' : 'text-[#13306B]'
        }`}
      >
        SigLa
      </Text>

      <Text
        className={`text-sm mt-2 font-medium text-center ${
          isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
        }`}
      >
        Filipino Sign Language Translator
      </Text>
    </View>
  );
};
