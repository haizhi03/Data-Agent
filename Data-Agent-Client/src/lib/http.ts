import axios from 'axios';
import { ErrorCode } from '../constants/errorCode';
import { X_ORG_ID, X_WORKSPACE_TYPE } from '../constants/workspaceHeaders';
import { useRuntimeStore } from '../store/runtimeStore';
import { failLocalRuntime, localRequestTransport } from './localRuntime';

const http = axios.create({
    timeout: 10000,
    headers: {
        'Content-Type': 'application/json',
    },
});

function redactLocalError(error: any) {
    for (const config of [error?.config, error?.response?.config]) {
        if (config) delete config.data;
        const headers = config?.headers;
        if (typeof headers?.delete === 'function') headers.delete('Authorization');
        else if (headers) for (const key of Object.keys(headers)) if (key.toLowerCase() === 'authorization') delete headers[key];
    }
    if (error) delete error.request;
    return error;
}

http.interceptors.request.use((config) => {
    const local = localRequestTransport(config.url);
    config.baseURL = local.baseURL;
    if (local.authorization) config.headers.Authorization = local.authorization;
    else config.headers.delete('Authorization');
    config.headers.set(X_WORKSPACE_TYPE, 'PERSONAL');
    config.headers.delete(X_ORG_ID);
    config.headers.set('X-Local-Runtime-Id', useRuntimeStore.getState().capabilities!.runtimeInstanceId);
    return config;
});

http.interceptors.response.use(
    (response) => {
        const responseData = response.data;
        if (responseData && typeof responseData === 'object' && 'code' in responseData) {
            const code = responseData.code;
            if (code !== ErrorCode.SUCCESS) {
                const error: any = new Error(responseData.message || 'Request failed');
                error.response = {
                    status: response.status,
                    data: responseData,
                };
                error.config = response.config;
                return Promise.reject(redactLocalError(error));
            }
            if ('data' in responseData) {
                response.data = responseData.data;
            }
        }
        return response;
    },
    (error) => {
        if (error.response?.status === 401) failLocalRuntime();
        return Promise.reject(redactLocalError(error));
    }
);

export default http;
