import React, { useState, useRef } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  Alert,
  Keyboard,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useWebSocket, WsStatus } from '../context/WebSocketContext';
import { Colors } from '../utils/theme';

export default function ConnectScreen() {
  const { state, actions } = useWebSocket();
  const { wsUrl, status, errorMsg, savedUrls, savedRemarks, autoConnecting } = state;

  const isConnected = status === WsStatus.CONNECTED;
  const isConnecting = status === WsStatus.CONNECTING;
  const isBusy = isConnecting || autoConnecting;

  const [editingUrl, setEditingUrl] = useState(null);
  const [editingText, setEditingText] = useState('');
  const editInputRef = useRef(null);

  const handleConnect = () => {
    Keyboard.dismiss();
    actions.connect();
  };

  const handleLongPressUrl = (url, idx) => {
    setEditingUrl(url);
    setEditingText(savedRemarks[idx] || '');
    setTimeout(() => editInputRef.current?.focus(), 100);
  };

  const handleSaveRemark = () => {
    if (editingUrl) {
      actions.saveRemark(editingUrl, editingText);
      setEditingUrl(null);
      Keyboard.dismiss();
    }
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        {/* ── 头部标题 ── */}
        <View style={styles.header}>
          <Text style={styles.title}>SYSMON</Text>
          <View style={styles.statusRow}>
            <View style={[
              styles.statusDot,
              { backgroundColor: autoConnecting || isConnecting ? Colors.WarnOrange : Colors.TextMuted }
            ]} />
            <Text style={[
              styles.statusText,
              { color: autoConnecting || isConnecting ? Colors.WarnOrange : Colors.TextMuted }
            ]}>
              {autoConnecting ? 'AUTO CONNECTING' : isConnecting ? 'CONNECTING' : status === WsStatus.ERROR ? 'CONNECTION FAILED' : 'OFFLINE'}
            </Text>
          </View>
        </View>

        {/* ── 连接卡片 ── */}
        <View style={styles.card}>
          <Text style={[styles.cardLabel, { color: Colors.NeonBlue }]}>CONNECTION</Text>

          <View style={styles.inputRow}>
            <Text style={[styles.inputIcon, { color: isConnected ? Colors.CpuGreen : Colors.TextMuted }]}>⌁</Text>
            <TextInput
              style={[
                styles.input,
                { color: Colors.TextPrimary, borderColor: Colors.BorderColor },
                !isConnected && !isBusy && { borderColor: Colors.NeonBlue + '44' },
              ]}
              value={wsUrl}
              onChangeText={actions.updateUrl}
              placeholder="ws://192.168.x.x:9001"
              placeholderTextColor={Colors.TextMuted}
              editable={!isConnected && !isBusy}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              returnKeyType="done"
              onSubmitEditing={Keyboard.dismiss}
            />
          </View>

          {status === WsStatus.ERROR && (
            <Text style={styles.errorText}>⚠ {errorMsg || '连接失败'}</Text>
          )}

          <TouchableOpacity
            style={[
              styles.connectBtn,
              isConnected && styles.disconnectBtn,
              isBusy && styles.busyBtn,
            ]}
            onPress={isConnected ? actions.disconnect : handleConnect}
            disabled={isBusy}
            activeOpacity={0.7}
          >
            {isBusy ? (
              <View style={styles.btnInner}>
                <ActivityIndicator size="small" color={Colors.WarnOrange} />
                <Text style={[styles.btnText, { color: Colors.WarnOrange }]}>
                  {autoConnecting ? 'AUTO CONNECTING...' : 'CONNECTING...'}
                </Text>
              </View>
            ) : isConnected ? (
              <Text style={[styles.btnText, { color: Colors.DangerRed }]}>✕  DISCONNECT</Text>
            ) : (
              <Text style={[styles.btnText, { color: Colors.NeonBlue }]}>⌁  CONNECT</Text>
            )}
          </TouchableOpacity>
        </View>

        {/* ── 已保存的 URL 卡片 ── */}
        {savedUrls.length > 0 && (
          <View style={[styles.card, { borderColor: Colors.MemPurple + '44' }]}>
            <View style={styles.savedHeader}>
              <Text style={[styles.cardLabel, { color: Colors.MemPurple }]}>
                SAVED  {savedUrls.length}/10
              </Text>
              <Text style={styles.savedHint}>点击连接  长按编辑备注</Text>
            </View>

            {savedUrls.map((url, idx) => {
              const remark = savedRemarks[idx] || '';
              const isActive = url === state.connectedUrl && isConnected;

              return (
                <View key={url} style={styles.savedItemWrapper}>
                  <TouchableOpacity
                    style={[
                      styles.savedItem,
                      isActive && styles.savedItemActive,
                    ]}
                    onPress={() => {
                      if (!isConnected && !isBusy) {
                        actions.connectToUrl(url);
                      }
                    }}
                    onLongPress={() => handleLongPressUrl(url, idx)}
                    activeOpacity={0.7}
                    disabled={isConnected || isBusy}
                  >
                    <View style={[styles.savedDot, { backgroundColor: isActive ? Colors.LiveGreen : Colors.TextMuted }]} />
                    <View style={styles.savedTextGroup}>
                      {remark.length > 0 && (
                        <Text style={styles.savedRemark} numberOfLines={1}>{remark}</Text>
                      )}
                      <Text
                        style={[styles.savedUrl, { color: isActive ? Colors.LiveGreen : Colors.TextSecondary }]}
                        numberOfLines={1}
                      >
                        {url}
                      </Text>
                    </View>
                    {!isActive && (
                      <TouchableOpacity
                        style={styles.removeBtn}
                        onPress={() => {
                          Alert.alert('删除', `确认删除 ${url}?`, [
                            { text: '取消' },
                            { text: '删除', style: 'destructive', onPress: () => actions.removeUrl(url) },
                          ]);
                        }}
                        hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                      >
                        <Text style={styles.removeText}>✕</Text>
                      </TouchableOpacity>
                    )}
                  </TouchableOpacity>

                  {/* 内联备注编辑 */}
                  {editingUrl === url && (
                    <View style={styles.editRow}>
                      <TextInput
                        ref={editInputRef}
                        style={styles.editInput}
                        value={editingText}
                        onChangeText={setEditingText}
                        placeholder="输入备注（如：家里Mac）"
                        placeholderTextColor={Colors.TextMuted}
                        returnKeyType="done"
                        onSubmitEditing={handleSaveRemark}
                        autoFocus
                      />
                      <TouchableOpacity style={styles.editSaveBtn} onPress={handleSaveRemark}>
                        <Text style={{ color: Colors.MemPurple, fontSize: 16 }}>✓</Text>
                      </TouchableOpacity>
                      <TouchableOpacity
                        style={styles.editCancelBtn}
                        onPress={() => { setEditingUrl(null); Keyboard.dismiss(); }}
                      >
                        <Text style={{ color: Colors.TextMuted, fontSize: 16 }}>✕</Text>
                      </TouchableOpacity>
                    </View>
                  )}
                </View>
              );
            })}
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: Colors.BgDeep,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingTop: 20,
    gap: 12,
  },
  header: {
    alignItems: 'center',
    marginBottom: 8,
  },
  title: {
    color: Colors.TextPrimary,
    fontSize: 28,
    fontWeight: 'bold',
    fontFamily: 'monospace',
    letterSpacing: 6,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 6,
  },
  statusDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  statusText: {
    fontSize: 11,
    fontFamily: 'monospace',
    fontWeight: 'bold',
    letterSpacing: 1.5,
  },
  card: {
    backgroundColor: Colors.BgCard,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: Colors.NeonBlue + '33',
    padding: 16,
    gap: 10,
  },
  cardLabel: {
    fontSize: 11,
    fontFamily: 'monospace',
    fontWeight: 'bold',
    letterSpacing: 2,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  inputIcon: {
    fontSize: 18,
  },
  input: {
    flex: 1,
    height: 44,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    fontSize: 12,
    fontFamily: 'monospace',
    backgroundColor: Colors.BgCardAlt,
  },
  errorText: {
    color: Colors.DangerRed,
    fontSize: 11,
    fontFamily: 'monospace',
  },
  connectBtn: {
    height: 44,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: Colors.NeonBlue + '88',
    backgroundColor: Colors.NeonBlueFade,
    justifyContent: 'center',
    alignItems: 'center',
  },
  disconnectBtn: {
    borderColor: Colors.DangerRed + '88',
    backgroundColor: Colors.DangerRed + '22',
  },
  busyBtn: {
    borderColor: Colors.WarnOrange + '44',
    backgroundColor: Colors.BgCardAlt,
  },
  btnInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  btnText: {
    fontSize: 12,
    fontFamily: 'monospace',
    fontWeight: 'bold',
    letterSpacing: 1,
  },
  savedHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  savedHint: {
    color: Colors.TextMuted,
    fontSize: 9,
    fontFamily: 'monospace',
  },
  savedItemWrapper: {
    borderRadius: 8,
    overflow: 'hidden',
    backgroundColor: Colors.BgSlate + '66',
  },
  savedItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 8,
    gap: 8,
  },
  savedItemActive: {
    backgroundColor: Colors.MemPurple + '1F',
  },
  savedDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  savedTextGroup: {
    flex: 1,
    gap: 2,
  },
  savedRemark: {
    color: Colors.MemPurple,
    fontSize: 10,
    fontFamily: 'monospace',
    fontWeight: '600',
  },
  savedUrl: {
    fontSize: 11,
    fontFamily: 'monospace',
  },
  removeBtn: {
    padding: 4,
  },
  removeText: {
    color: Colors.TextMuted,
    fontSize: 14,
  },
  editRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: Colors.BgCard,
    paddingHorizontal: 10,
    paddingVertical: 6,
    gap: 6,
  },
  editInput: {
    flex: 1,
    height: 36,
    borderWidth: 1,
    borderRadius: 8,
    borderColor: Colors.MemPurple + '88',
    paddingHorizontal: 10,
    color: Colors.TextPrimary,
    fontSize: 11,
    fontFamily: 'monospace',
    backgroundColor: Colors.BgCardAlt,
  },
  editSaveBtn: {
    width: 32,
    height: 32,
    justifyContent: 'center',
    alignItems: 'center',
  },
  editCancelBtn: {
    width: 32,
    height: 32,
    justifyContent: 'center',
    alignItems: 'center',
  },
});
