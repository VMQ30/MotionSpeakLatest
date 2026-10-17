import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  ScrollView,
  SafeAreaView,
  TouchableOpacity,
  Image,
} from 'react-native';
import { Header } from '../components/Header';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import {
  IconSpeaker,
  IconHeart,
  IconPlay,
  IconPause,
  IconReplay,
  IconFullscreen,
  IconDownload,
  IconHand,
} from '../components/Icons';
import { Storage } from '../services/storage';
import { NativeBridge } from '../services/nativeModules';
import { WordBankWord } from '../types';

interface WordDetailScreenProps {
  wordId: number;
  onBack: () => void;
  onNavigate: (screen: string, params?: any) => void;
  isDarkMode?: boolean;
}

export const WordDetailScreen: React.FC<WordDetailScreenProps> = ({
  wordId,
  onBack,
  onNavigate,
  isDarkMode = false,
}) => {
  const [word, setWord] = useState<WordBankWord | null>(null);
  const [isFavorite, setIsFavorite] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(1.0);

  useEffect(() => {
    const allWords = Storage.getCachedWords();
    const found = allWords.find((w) => w.id === wordId);
    if (found) {
      setWord(found);
    }
    setIsFavorite(Storage.isFavorite(wordId));
  }, [wordId]);

  const handleSpeak = () => {
    if (!word) return;
    const settings = Storage.getSettings();
    NativeBridge.speak(word.label, settings.volume, settings.voiceType);
  };

  const handleToggleFavorite = () => {
    if (!word) return;
    const nowFav = Storage.toggleFavorite(word.id);
    setIsFavorite(nowFav);
  };

  const handleDownload = () => {
    if (!word?.video_url) return;
    NativeBridge.downloadDemoVideo(word.video_url, word.id);
  };

  if (!word) {
    return (
      <SafeAreaView
        className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
      >
        <Header title="Word Detail" onBack={onBack} isDarkMode={isDarkMode} />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView
      className={`flex-1 ${isDarkMode ? 'bg-[#0C1430]' : 'bg-white'}`}
    >
      <ScrollView className="flex-1" contentContainerStyle={{ paddingBottom: 40 }}>
        <View className="px-5 pt-3">
          <Header
            title={word.category || 'Word Detail'}
            onBack={onBack}
            isDarkMode={isDarkMode}
          />

          {/* Word Name & Audio Pronunciation */}
          <View className="flex-row items-center justify-between mt-3 mb-1">
            <View className="flex-1">
              <Text
                className={`text-3xl font-extrabold ${
                  isDarkMode ? 'text-[#C9D8FA]' : 'text-[#13306B]'
                }`}
              >
                {word.label}
              </Text>
              {word.filipino_translation ? (
                <Text
                  className={`text-sm mt-1 font-medium ${
                    isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                  }`}
                >
                  {word.filipino_translation}
                </Text>
              ) : null}
            </View>

            <TouchableOpacity
              onPress={handleSpeak}
              className={`w-12 h-12 rounded-full items-center justify-center shadow-md ${
                isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
              activeOpacity={0.7}
            >
              <IconSpeaker
                size={24}
                color={isDarkMode ? '#C9D8FA' : '#13306B'}
              />
            </TouchableOpacity>
          </View>

          {/* Tags / Badges */}
          <View className="flex-row items-center mt-3 mb-5 space-x-2">
            <View
              className={`px-3 py-1 rounded-full mr-2 ${
                isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
            >
              <Text
                className={`text-xs font-semibold ${
                  isDarkMode ? 'text-[#B8CCF8]' : 'text-[#13306B]'
                }`}
              >
                {word.sign_type || 'FSL'}
              </Text>
            </View>

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
                {word.vocabulary === 'letters' ? 'Alphabet Letter' : 'Word Gesture'}
              </Text>
            </View>
          </View>

          {/* Media Stage: Demonstration Video/Image */}
          <Card
            variant="outlined"
            isDarkMode={isDarkMode}
            className="p-0 overflow-hidden mb-5"
          >
            <View className="w-full h-64 bg-black items-center justify-center relative">
              {word.thumbnail_url ? (
                <Image
                  source={{ uri: word.thumbnail_url }}
                  className="w-full h-full"
                  resizeMode="contain"
                />
              ) : (
                <View className="items-center justify-center">
                  <IconHand size={48} color="rgba(255, 255, 255, 0.4)" />
                  <Text className="text-white/60 text-xs mt-2">
                    Video Demonstration
                  </Text>
                </View>
              )}

              {/* Play / Pause Overlay Button */}
              <TouchableOpacity
                onPress={() => setIsPlaying(!isPlaying)}
                className="w-14 h-14 rounded-full bg-black/60 items-center justify-center shadow-lg"
                activeOpacity={0.8}
              >
                {isPlaying ? (
                  <IconPause size={28} color="#FFFFFF" />
                ) : (
                  <IconPlay size={28} color="#FFFFFF" />
                )}
              </TouchableOpacity>
            </View>

            {/* Playback Controls Bar */}
            <View
              className={`flex-row items-center justify-between px-4 py-3 border-t ${
                isDarkMode ? 'border-[#233A73] bg-[#111B3E]' : 'border-[#E2E8F4] bg-[#F8FAFC]'
              }`}
            >
              <View className="flex-row items-center space-x-2">
                <TouchableOpacity
                  onPress={() => setIsPlaying(!isPlaying)}
                  className="p-1 mr-2"
                >
                  {isPlaying ? (
                    <IconPause
                      size={20}
                      color={isDarkMode ? '#C9D8FA' : '#13306B'}
                    />
                  ) : (
                    <IconPlay
                      size={20}
                      color={isDarkMode ? '#C9D8FA' : '#13306B'}
                    />
                  )}
                </TouchableOpacity>

                <TouchableOpacity className="p-1">
                  <IconReplay
                    size={20}
                    color={isDarkMode ? '#C9D8FA' : '#13306B'}
                  />
                </TouchableOpacity>
              </View>

              {/* Speed Toggle Chips */}
              <View className="flex-row items-center space-x-1">
                {[0.5, 0.75, 1.0].map((s) => (
                  <TouchableOpacity
                    key={s}
                    onPress={() => setPlaybackSpeed(s)}
                    className={`px-2 py-0.5 rounded-full ${
                      playbackSpeed === s
                        ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                        : 'bg-transparent'
                    }`}
                  >
                    <Text
                      className={`text-[11px] font-bold ${
                        playbackSpeed === s
                          ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                          : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                      }`}
                    >
                      {s}x
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
            </View>
          </Card>

          {/* Action Buttons */}
          <View className="space-y-3">
            <Button
              title={isFavorite ? 'Favorited' : 'Add to Favorites'}
              variant={isFavorite ? 'primary' : 'secondary'}
              onPress={handleToggleFavorite}
              icon={
                <IconHeart
                  size={18}
                  filled={isFavorite}
                  color={isFavorite ? '#FFFFFF' : isDarkMode ? '#C9D8FA' : '#13306B'}
                />
              }
              isDarkMode={isDarkMode}
              className="mb-3"
            />

            {word.video_url && (
              <Button
                title="Download for offline use"
                variant="secondary"
                onPress={handleDownload}
                icon={
                  <IconDownload
                    size={18}
                    color={isDarkMode ? '#C9D8FA' : '#13306B'}
                  />
                }
                isDarkMode={isDarkMode}
                className="mb-3"
              />
            )}

            <Button
              title="Practice in Camera"
              variant="primary"
              onPress={() => onNavigate('Translator')}
              icon={<IconHand size={18} color="#FFFFFF" />}
              isDarkMode={isDarkMode}
            />
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
};
