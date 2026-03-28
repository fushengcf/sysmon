import React from 'react';
import { StatusBar } from 'expo-status-bar';
import { NavigationContainer } from '@react-navigation/native';
import { createStackNavigator } from '@react-navigation/stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';

import { WebSocketProvider, useWebSocket, WsStatus } from './src/context/WebSocketContext';
import ConnectScreen from './src/screens/ConnectScreen';
import DashboardScreen from './src/screens/DashboardScreen';
import { Colors } from './src/utils/theme';

const Stack = createStackNavigator();

function AppNavigator() {
  const { state } = useWebSocket();
  const isConnected = state.status === WsStatus.CONNECTED;

  return (
    <NavigationContainer
      theme={{
        dark: true,
        colors: {
          primary: Colors.NeonBlue,
          background: Colors.BgDeep,
          card: Colors.BgCard,
          text: Colors.TextPrimary,
          border: Colors.BorderColor,
          notification: Colors.WarnOrange,
        },
      }}
    >
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          animationEnabled: true,
          cardStyle: { backgroundColor: Colors.BgDeep },
        }}
      >
        {isConnected ? (
          <Stack.Screen
            name="Dashboard"
            component={DashboardScreen}
            options={{
              animationEnabled: true,
              cardStyleInterpolator: ({ current }) => ({
                cardStyle: { opacity: current.progress },
              }),
            }}
          />
        ) : (
          <Stack.Screen
            name="Connect"
            component={ConnectScreen}
            options={{
              animationEnabled: true,
              cardStyleInterpolator: ({ current }) => ({
                cardStyle: { opacity: current.progress },
              }),
            }}
          />
        )}
      </Stack.Navigator>
    </NavigationContainer>
  );
}

export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <WebSocketProvider>
          <StatusBar style="light" backgroundColor={Colors.BgDeep} />
          <AppNavigator />
        </WebSocketProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
