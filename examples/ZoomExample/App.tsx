import {
  Button,
  StatusBar,
  StyleSheet,
  useColorScheme,
  View,
} from 'react-native';
import { mediaDevices, RTCView } from 'react-native-webrtc';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { use, useState } from 'react';

const streamPromise = mediaDevices.getUserMedia({ video: { facingMode: "environment"} });

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  const [zoom, setZoom] = useState<number>(0);
  const stream = use(streamPromise);

  const increaseZoom = () => {
    setZoom(zoom + 1);
    stream.getVideoTracks().at(0)?._setZoom(zoom)
  };

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <View style={styles.container}>
        <RTCView streamURL={stream.toURL()} style={{ flex: 1 }} />
        <Button title="increase zoom" onPress={increaseZoom} />
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
});

export default App;
