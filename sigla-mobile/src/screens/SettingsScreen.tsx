import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  ScrollView,
  SafeAreaView,
  TouchableOpacity,
  Switch,
} from 'react-native';
import { Header } from '../components/Header';
import { Card } from '../components/Card';
import { Button } from '../components/Button';
import { BottomNavBar } from '../components/BottomNavBar';
import { EditNameModal } from '../components/EditNameModal';
import { ConfirmDialog } from '../components/ConfirmDialog';
import {
  IconSpeaker,
  IconCheck,
} from '../components/Icons';
import { Storage } from '../services/storage';
import { NativeBridge } from '../services/nativeModules';
import { AppSettingsState } from '../types';

interface SettingsScreenProps {
  onNavigate: (screen: string, params?: any) => void;
  onThemeChange?: (isDark: boolean) => void;
  isDarkMode?: boolean;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  onNavigate,
  onThemeChange,
  isDarkMode = false,
}) => {
  const [settings, setSettings] = useState<AppSettingsState>(Storage.getSettings());
  const [showEditName, setShowEditName] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [modelVersion, setModelVersion] = useState<string>('v1.10');
  const [checkingModel, setCheckingModel] = useState(false);

  useEffect(() => {
    setSettings(Storage.getSettings());
    NativeBridge.getModelStatus().then((res) => {
      if (res.version) setModelVersion(res.version);
    });
  }, []);

  const updateSetting = (partial: Partial<AppSettingsState>) => {
    Storage.updateSettings(partial);
    const updated = Storage.getSettings();
    setSettings(updated);
    if (partial.isDarkMode !== undefined && onThemeChange) {
      onThemeChange(partial.isDarkMode);
    }
  };

  const handleTestVoice = () => {
    NativeBridge.speak('Hello! This is SigLa speaking.', settings.volume, settings.voiceType);
  };

  const handleCheckModelUpdate = async () => {
    setCheckingModel(true);
    const res = await NativeBridge.checkAndUpdateModel();
    if (res.version) setModelVersion(res.version);
    setCheckingModel(false);
  };

  const handleResetDefaults = () => {
    Storage.resetSettings();
    const updated = Storage.getSettings();
    setSettings(updated);
    if (onThemeChange) {
      onThemeChange(updated.isDarkMode);
    }
    setShowResetConfirm(false);
  };

  const userInitial = settings.userName?.trim()
    ? settings.userName.trim().charAt(0).toUpperCase()
    : '?';

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <ScrollView className="flex-1" contentContainerStyle={{ paddingBottom: 110 }}>
        <View className="px-5 pt-3">
          <Header
            title="Settings"
            subtitle="Application preferences"
            isDarkMode={isDarkMode}
          />

          {/* Profile Card */}
          <TouchableOpacity
            onPress={() => setShowEditName(true)}
            activeOpacity={0.85}
            className="mt-4"
          >
            <Card variant="brand" isDarkMode={isDarkMode} className="p-4">
              <View className="flex-row items-center justify-between">
                <View className="flex-row items-center flex-1 mr-3">
                  <View
                    className={`w-12 h-12 rounded-full items-center justify-center mr-3.5 ${
                      isDarkMode ? 'bg-[#13306B]/15' : 'bg-white/15'
                    }`}
                  >
                    <Text
                      className={`text-xl font-bold ${
                        isDarkMode ? 'text-[#13306B]' : 'text-white'
                      }`}
                    >
                      {userInitial}
                    </Text>
                  </View>

                  <View className="flex-1">
                    <Text
                      className={`text-base font-bold ${
                        isDarkMode ? 'text-[#13306B]' : 'text-white'
                      }`}
                      numberOfLines={1}
                    >
                      {settings.userName || 'Add your name'}
                    </Text>
                    <Text
                      className={`text-xs mt-0.5 ${
                        isDarkMode ? 'text-[#13306B]/80' : 'text-white/80'
                      }`}
                    >
                      Tap to change your name
                    </Text>
                  </View>
                </View>
              </View>
            </Card>
          </TouchableOpacity>

          {/* Audio & Speech Section */}
          <View className="mt-6">
            <Text
              className={`text-xs font-bold uppercase tracking-wider mb-2.5 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              Speech & Audio
            </Text>

            <Card variant="tint" isDarkMode={isDarkMode} className="p-4 space-y-4">
              {/* Voice Type Selection */}
              <View className="flex-row items-center justify-between">
                <Text
                  className={`text-sm font-semibold ${
                    isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                  }`}
                >
                  Voice Type
                </Text>

                <View className="flex-row items-center space-x-2">
                  <TouchableOpacity
                    onPress={() => updateSetting({ voiceType: 'female' })}
                    className={`px-3.5 py-1.5 rounded-full mr-2 ${
                      settings.voiceType === 'female'
                        ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                        : isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                    }`}
                  >
                    <Text
                      className={`text-xs font-bold ${
                        settings.voiceType === 'female'
                          ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                          : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                      }`}
                    >
                      Female
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    onPress={() => updateSetting({ voiceType: 'male' })}
                    className={`px-3.5 py-1.5 rounded-full ${
                      settings.voiceType === 'male'
                        ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                        : isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                    }`}
                  >
                    <Text
                      className={`text-xs font-bold ${
                        settings.voiceType === 'male'
                          ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                          : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                      }`}
                    >
                      Male
                    </Text>
                  </TouchableOpacity>
                </View>
              </View>

              {/* Volume Slider / Buttons */}
              <View className="flex-row items-center justify-between pt-2">
                <View>
                  <Text
                    className={`text-sm font-semibold ${
                      isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                    }`}
                  >
                    Speech Volume
                  </Text>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    {settings.volume}%
                  </Text>
                </View>

                <View className="flex-row items-center space-x-2">
                  {[20, 50, 80, 100].map((v) => (
                    <TouchableOpacity
                      key={v}
                      onPress={() => updateSetting({ volume: v })}
                      className={`px-2.5 py-1 rounded-lg ${
                        settings.volume === v
                          ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                          : isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                      }`}
                    >
                      <Text
                        className={`text-[11px] font-bold ${
                          settings.volume === v
                            ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                            : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                        }`}
                      >
                        {v}%
                      </Text>
                    </TouchableOpacity>
                  ))}
                </View>
              </View>

              {/* Test Voice Button */}
              <View className="pt-2">
                <Button
                  title="Test Speech Output"
                  variant="secondary"
                  onPress={handleTestVoice}
                  icon={
                    <IconSpeaker
                      size={18}
                      color={isDarkMode ? '#C9D8FA' : '#13306B'}
                    />
                  }
                  isDarkMode={isDarkMode}
                />
              </View>
            </Card>
          </View>

          {/* Appearance Section */}
          <View className="mt-6">
            <Text
              className={`text-xs font-bold uppercase tracking-wider mb-2.5 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              Appearance
            </Text>

            <Card variant="tint" isDarkMode={isDarkMode} className="p-4">
              <View className="flex-row items-center justify-between">
                <View className="flex-1 mr-4">
                  <Text
                    className={`text-sm font-semibold ${
                      isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                    }`}
                  >
                    Dark Mode
                  </Text>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    {settings.isDarkMode
                      ? 'Currently using Dark mode'
                      : 'Currently using Light mode'}
                  </Text>
                </View>

                <Switch
                  value={settings.isDarkMode}
                  onValueChange={(val) => updateSetting({ isDarkMode: val })}
                  trackColor={{ false: '#D7DBE4', true: '#13306B' }}
                  thumbColor={settings.isDarkMode ? '#C9D8FA' : '#FFFFFF'}
                />
              </View>
            </Card>
          </View>

          {/* Translation Preferences Section */}
          <View className="mt-6">
            <Text
              className={`text-xs font-bold uppercase tracking-wider mb-2.5 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              Translation
            </Text>

            <Card variant="tint" isDarkMode={isDarkMode} className="p-4 space-y-4">
              {/* Default Mode */}
              <View className="flex-row items-center justify-between">
                <View className="flex-1 mr-4">
                  <Text
                    className={`text-sm font-semibold ${
                      isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                    }`}
                  >
                    Default Mode
                  </Text>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    {settings.translationMode === 'tap'
                      ? 'Tap-to-sign (records 1 gesture)'
                      : 'Live continuous recognition'}
                  </Text>
                </View>

                <View className="flex-row items-center space-x-2">
                  <TouchableOpacity
                    onPress={() => updateSetting({ translationMode: 'tap' })}
                    className={`px-3 py-1.5 rounded-full mr-2 ${
                      settings.translationMode === 'tap'
                        ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                        : isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                    }`}
                  >
                    <Text
                      className={`text-xs font-bold ${
                        settings.translationMode === 'tap'
                          ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                          : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                      }`}
                    >
                      Tap
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    onPress={() => updateSetting({ translationMode: 'live' })}
                    className={`px-3 py-1.5 rounded-full ${
                      settings.translationMode === 'live'
                        ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                        : isDarkMode ? 'bg-[#111B3E]' : 'bg-[#D9E4FA]'
                    }`}
                  >
                    <Text
                      className={`text-xs font-bold ${
                        settings.translationMode === 'live'
                          ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                          : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                      }`}
                    >
                      Live
                    </Text>
                  </TouchableOpacity>
                </View>
              </View>

              {/* Show Filipino Translation */}
              <View className="flex-row items-center justify-between pt-2">
                <View className="flex-1 mr-4">
                  <Text
                    className={`text-sm font-semibold ${
                      isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                    }`}
                  >
                    Show Filipino Meaning
                  </Text>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    Displays Filipino subtitles with recognized English words
                  </Text>
                </View>

                <Switch
                  value={settings.showFilipino}
                  onValueChange={(val) => updateSetting({ showFilipino: val })}
                  trackColor={{ false: '#D7DBE4', true: '#13306B' }}
                  thumbColor={settings.showFilipino ? '#C9D8FA' : '#FFFFFF'}
                />
              </View>
            </Card>
          </View>

          {/* Recognition Model Status Section */}
          <View className="mt-6">
            <Text
              className={`text-xs font-bold uppercase tracking-wider mb-2.5 ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              Offline Recognition Models
            </Text>

            <Card variant="tint" isDarkMode={isDarkMode} className="p-4">
              <View className="flex-row items-center justify-between mb-3">
                <View>
                  <Text
                    className={`text-sm font-semibold ${
                      isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                    }`}
                  >
                    Installed Version
                  </Text>
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                  >
                    {modelVersion}
                  </Text>
                </View>

                <Button
                  title="Check for updates"
                  variant="chip"
                  onPress={handleCheckModelUpdate}
                  loading={checkingModel}
                  isDarkMode={isDarkMode}
                />
              </View>
            </Card>
          </View>

          {/* Quick Actions & Reset */}
          <View className="mt-6 space-y-3">
            <Button
              title="Replay Onboarding Tutorial"
              variant="secondary"
              onPress={() => {
                Storage.updateSettings({ isOnboardingDone: false });
                onNavigate('Onboarding');
              }}
              isDarkMode={isDarkMode}
              className="mb-3"
            />

            <Button
              title="Reset Settings to Default"
              variant="chip"
              onPress={() => setShowResetConfirm(true)}
              isDarkMode={isDarkMode}
            />
          </View>

          {/* About SigLa Footer */}
          <View className="items-center mt-10 mb-4">
            <Text
              className={`text-xs font-semibold ${
                isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
              }`}
            >
              SigLa Mobile v1.10
            </Text>
            <Text
              className={`text-[10px] mt-1 ${
                isDarkMode ? 'text-[#62739D]' : 'text-[#94A3B8]'
              }`}
            >
              Filipino Sign Language Recognition
            </Text>
          </View>
        </View>
      </ScrollView>

      {/* Edit Name Modal */}
      <EditNameModal
        visible={showEditName}
        initialValue={settings.userName}
        onSave={(name) => {
          updateSetting({ userName: name || null });
          setShowEditName(false);
        }}
        onCancel={() => setShowEditName(false)}
        isDarkMode={isDarkMode}
      />

      {/* Reset Confirmation Dialog */}
      <ConfirmDialog
        visible={showResetConfirm}
        title="Reset to default?"
        message="All settings will be restored to their original configuration."
        confirmLabel="Reset"
        variant="danger"
        onConfirm={handleResetDefaults}
        onCancel={() => setShowResetConfirm(false)}
        isDarkMode={isDarkMode}
      />

      {/* Persistent Bottom Nav */}
      <BottomNavBar
        currentTab="settings"
        onSelectTab={(tab) => {
          if (tab === 'settings') return;
          if (tab === 'home') onNavigate('Home');
          if (tab === 'word_bank') onNavigate('WordBank');
          if (tab === 'translator') onNavigate('Translator');
          if (tab === 'history') onNavigate('History');
        }}
        isDarkMode={isDarkMode}
      />
    </SafeAreaView>
  );
};
