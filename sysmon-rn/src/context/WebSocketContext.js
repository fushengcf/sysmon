import React, { createContext, useContext, useReducer, useEffect, useRef, useCallback } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';

// ─── 常量 ──────────────────────────────────────────────────────────────────────
const MAX_HISTORY = 60;
const STORAGE_KEY_URLS = '@sysmon_saved_urls';
const STORAGE_KEY_REMARKS = '@sysmon_saved_remarks';

// ─── 连接状态枚举 ───────────────────────────────────────────────────────────────
export const WsStatus = {
  DISCONNECTED: 'DISCONNECTED',
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  ERROR: 'ERROR',
};

// ─── 初始状态 ───────────────────────────────────────────────────────────────────
const initialState = {
  status: WsStatus.DISCONNECTED,
  errorMsg: '',
  wsUrl: 'ws://192.168.1.100:9001',
  connectedUrl: '',
  metrics: null,
  cpuHistory: [],
  memHistory: [],
  netRxHistory: [],
  netTxHistory: [],
  savedUrls: [],
  savedRemarks: [],
  autoConnecting: false,
};

// ─── Reducer ───────────────────────────────────────────────────────────────────
function reducer(state, action) {
  switch (action.type) {
    case 'SET_STATUS':
      return { ...state, status: action.payload, errorMsg: '' };
    case 'SET_ERROR':
      return { ...state, status: WsStatus.ERROR, errorMsg: action.payload };
    case 'SET_URL':
      return { ...state, wsUrl: action.payload };
    case 'SET_CONNECTED_URL':
      return { ...state, connectedUrl: action.payload };
    case 'SET_METRICS': {
      const m = action.payload;
      return {
        ...state,
        metrics: m,
        cpuHistory: [...state.cpuHistory, m.cpu_usage_percent].slice(-MAX_HISTORY),
        memHistory: [...state.memHistory, m.memory_usage_percent].slice(-MAX_HISTORY),
        netRxHistory: [...state.netRxHistory, m.net_rx_kbps].slice(-MAX_HISTORY),
        netTxHistory: [...state.netTxHistory, m.net_tx_kbps].slice(-MAX_HISTORY),
      };
    }
    case 'CLEAR_HISTORY':
      return {
        ...state,
        metrics: null,
        cpuHistory: [],
        memHistory: [],
        netRxHistory: [],
        netTxHistory: [],
      };
    case 'SET_SAVED_URLS':
      return { ...state, savedUrls: action.payload };
    case 'SET_SAVED_REMARKS':
      return { ...state, savedRemarks: action.payload };
    case 'SET_AUTO_CONNECTING':
      return { ...state, autoConnecting: action.payload };
    default:
      return state;
  }
}

// ─── Context ───────────────────────────────────────────────────────────────────
const WebSocketContext = createContext(null);

export function WebSocketProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, initialState);
  const wsRef = useRef(null);
  const manuallyDisconnectedRef = useRef(false);
  const autoConnectTimerRef = useRef(null);
  const reconnectTimerRef = useRef(null);

  // ── 持久化加载已保存的 URL ────────────────────────────────────────────────────
  useEffect(() => {
    (async () => {
      try {
        const urlsStr = await AsyncStorage.getItem(STORAGE_KEY_URLS);
        const remarksStr = await AsyncStorage.getItem(STORAGE_KEY_REMARKS);
        const urls = urlsStr ? JSON.parse(urlsStr) : [];
        const remarks = remarksStr ? JSON.parse(remarksStr) : [];
        dispatch({ type: 'SET_SAVED_URLS', payload: urls });
        dispatch({ type: 'SET_SAVED_REMARKS', payload: remarks });
        // 加载完后尝试自动连接
        if (urls.length > 0) {
          setTimeout(() => tryAutoConnect(urls), 500);
        }
      } catch (e) {
        // 忽略读取错误
      }
    })();
    return () => {
      closeWs();
      clearTimeout(autoConnectTimerRef.current);
      clearTimeout(reconnectTimerRef.current);
    };
  }, []);

  // ── WebSocket 核心操作 ────────────────────────────────────────────────────────

  const closeWs = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.onopen = null;
      wsRef.current.onmessage = null;
      wsRef.current.onerror = null;
      wsRef.current.onclose = null;
      try { wsRef.current.close(); } catch (e) {}
      wsRef.current = null;
    }
  }, []);

  const connectTo = useCallback((url, onConnected, onFailed) => {
    closeWs();
    dispatch({ type: 'SET_STATUS', payload: WsStatus.CONNECTING });

    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      dispatch({ type: 'SET_STATUS', payload: WsStatus.CONNECTED });
      dispatch({ type: 'SET_CONNECTED_URL', payload: url });
      onConnected && onConnected();
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        dispatch({ type: 'SET_METRICS', payload: data });
      } catch (e) {
        // 忽略解析错误
      }
    };

    ws.onerror = () => {
      dispatch({ type: 'SET_ERROR', payload: '连接失败' });
      onFailed && onFailed();
    };

    ws.onclose = () => {
      if (wsRef.current === ws) {
        dispatch({ type: 'SET_STATUS', payload: WsStatus.DISCONNECTED });
        wsRef.current = null;
        // 断线后自动重连（非用户主动断开）
        if (!manuallyDisconnectedRef.current) {
          reconnectTimerRef.current = setTimeout(async () => {
            const urlsStr = await AsyncStorage.getItem(STORAGE_KEY_URLS);
            const urls = urlsStr ? JSON.parse(urlsStr) : [];
            if (urls.length > 0 && !manuallyDisconnectedRef.current) {
              tryAutoConnect(urls);
            }
          }, 8000);
        }
      }
    };
  }, [closeWs]);

  // ── 自动连接（依次尝试已保存的 URL）─────────────────────────────────────────

  const tryAutoConnect = useCallback(async (urlList) => {
    if (!urlList || urlList.length === 0) return;
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) return;

    dispatch({ type: 'SET_AUTO_CONNECTING', payload: true });

    for (const url of urlList) {
      if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) break;
      if (manuallyDisconnectedRef.current) break;

      await new Promise((resolve) => {
        dispatch({ type: 'SET_URL', payload: url });
        closeWs();

        const ws = new WebSocket(url);
        wsRef.current = ws;

        const timeout = setTimeout(() => {
          if (ws.readyState !== WebSocket.OPEN) {
            ws.onopen = null;
            ws.onmessage = null;
            ws.onerror = null;
            ws.onclose = null;
            try { ws.close(); } catch(e) {}
            if (wsRef.current === ws) wsRef.current = null;
            resolve(false);
          }
        }, 5000);

        ws.onopen = () => {
          clearTimeout(timeout);
          dispatch({ type: 'SET_STATUS', payload: WsStatus.CONNECTED });
          dispatch({ type: 'SET_CONNECTED_URL', payload: url });

          ws.onmessage = (event) => {
            try {
              const data = JSON.parse(event.data);
              dispatch({ type: 'SET_METRICS', payload: data });
            } catch (e) {}
          };

          ws.onerror = () => {
            dispatch({ type: 'SET_ERROR', payload: '连接失败' });
          };

          ws.onclose = () => {
            if (wsRef.current === ws) {
              dispatch({ type: 'SET_STATUS', payload: WsStatus.DISCONNECTED });
              wsRef.current = null;
              if (!manuallyDisconnectedRef.current) {
                reconnectTimerRef.current = setTimeout(async () => {
                  const urlsStr = await AsyncStorage.getItem(STORAGE_KEY_URLS);
                  const urls = urlsStr ? JSON.parse(urlsStr) : [];
                  if (urls.length > 0 && !manuallyDisconnectedRef.current) {
                    tryAutoConnect(urls);
                  }
                }, 8000);
              }
            }
          };

          resolve(true);
        };

        ws.onerror = () => {
          clearTimeout(timeout);
          if (wsRef.current === ws) wsRef.current = null;
          dispatch({ type: 'SET_STATUS', payload: WsStatus.DISCONNECTED });
          resolve(false);
        };
      });

      if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) break;

      // 下一个 URL 前稍等
      await new Promise(r => setTimeout(r, 300));
    }

    dispatch({ type: 'SET_AUTO_CONNECTING', payload: false });
  }, [closeWs]);

  // ── 持久化 URL ──────────────────────────────────────────────────────────────

  const persistUrls = useCallback(async (urls) => {
    await AsyncStorage.setItem(STORAGE_KEY_URLS, JSON.stringify(urls));
  }, []);

  const persistRemarks = useCallback(async (remarks) => {
    await AsyncStorage.setItem(STORAGE_KEY_REMARKS, JSON.stringify(remarks));
  }, []);

  // ── 对外操作 ─────────────────────────────────────────────────────────────────

  const actions = {
    updateUrl: (url) => dispatch({ type: 'SET_URL', payload: url }),

    connect: () => {
      manuallyDisconnectedRef.current = false;
      clearTimeout(reconnectTimerRef.current);
      const url = state.wsUrl;
      dispatch({ type: 'CLEAR_HISTORY' });
      connectTo(url);
    },

    disconnect: () => {
      manuallyDisconnectedRef.current = true;
      clearTimeout(autoConnectTimerRef.current);
      clearTimeout(reconnectTimerRef.current);
      dispatch({ type: 'SET_AUTO_CONNECTING', payload: false });
      closeWs();
      dispatch({ type: 'SET_STATUS', payload: WsStatus.DISCONNECTED });
      dispatch({ type: 'CLEAR_HISTORY' });
    },

    connectToUrl: (url) => {
      manuallyDisconnectedRef.current = false;
      clearTimeout(reconnectTimerRef.current);
      dispatch({ type: 'SET_URL', payload: url });
      dispatch({ type: 'CLEAR_HISTORY' });
      connectTo(url);
    },

    saveCurrentUrl: async () => {
      const url = state.wsUrl.trim();
      if (!url) return;
      let urls = [...state.savedUrls];
      if (!urls.includes(url)) {
        urls = [url, ...urls].slice(0, 10);
        dispatch({ type: 'SET_SAVED_URLS', payload: urls });
        await persistUrls(urls);
        // 同步更新 remarks 长度
        const remarks = [...state.savedRemarks, ''].slice(0, urls.length);
        dispatch({ type: 'SET_SAVED_REMARKS', payload: remarks });
        await persistRemarks(remarks);
      }
    },

    removeUrl: async (url) => {
      const idx = state.savedUrls.indexOf(url);
      if (idx === -1) return;
      const urls = state.savedUrls.filter((_, i) => i !== idx);
      const remarks = state.savedRemarks.filter((_, i) => i !== idx);
      dispatch({ type: 'SET_SAVED_URLS', payload: urls });
      dispatch({ type: 'SET_SAVED_REMARKS', payload: remarks });
      await persistUrls(urls);
      await persistRemarks(remarks);
    },

    saveRemark: async (url, remark) => {
      const idx = state.savedUrls.indexOf(url);
      if (idx === -1) return;
      const remarks = [...state.savedRemarks];
      while (remarks.length <= idx) remarks.push('');
      remarks[idx] = remark;
      dispatch({ type: 'SET_SAVED_REMARKS', payload: remarks });
      await persistRemarks(remarks);
    },

    switchToPrevUrl: () => {
      const { savedUrls, connectedUrl } = state;
      if (savedUrls.length < 2) return;
      const idx = savedUrls.indexOf(connectedUrl);
      const target = savedUrls[idx <= 0 ? savedUrls.length - 1 : idx - 1];
      actions.connectToUrl(target);
    },

    switchToNextUrl: () => {
      const { savedUrls, connectedUrl } = state;
      if (savedUrls.length < 2) return;
      const idx = savedUrls.indexOf(connectedUrl);
      const target = savedUrls[idx < 0 || idx >= savedUrls.length - 1 ? 0 : idx + 1];
      actions.connectToUrl(target);
    },
  };

  // 连接成功后自动保存 URL
  useEffect(() => {
    if (state.status === WsStatus.CONNECTED) {
      actions.saveCurrentUrl();
    }
  }, [state.status]);

  return (
    <WebSocketContext.Provider value={{ state, actions }}>
      {children}
    </WebSocketContext.Provider>
  );
}

export function useWebSocket() {
  const ctx = useContext(WebSocketContext);
  if (!ctx) throw new Error('useWebSocket must be used within WebSocketProvider');
  return ctx;
}
