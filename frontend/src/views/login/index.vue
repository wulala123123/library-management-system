<template>
  <div class="login-page">
    <div class="bg-layer">
      <img src="@/assets/images/library-bg.jpg" alt="library" class="bg-image" />
      <div class="bg-overlay"></div>
    </div>

    <div class="login-container">
      <div class="login-brand">
        <div class="brand-mark" @mouseenter="markHover = true" @mouseleave="markHover = false" :class="{ hovered: markHover }">
          <svg width="56" height="56" viewBox="0 0 56 56" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="56" height="56" rx="14" fill="rgba(255,255,255,0.15)" stroke="rgba(255,255,255,0.3)" stroke-width="1.5"/>
            <g transform="translate(11, 12)">
              <rect x="0" y="0" width="14" height="30" rx="2" fill="rgba(255,255,255,0.9)"/>
              <rect x="17" y="3" width="14" height="27" rx="2" fill="rgba(255,255,255,0.6)"/>
              <line x1="4" y1="7" x2="10" y2="7" stroke="#2c3e50" stroke-width="2.2" stroke-linecap="round"/>
              <line x1="4" y1="12" x2="10" y2="12" stroke="#2c3e50" stroke-width="1.8" stroke-linecap="round"/>
              <line x1="4" y1="17" x2="8" y2="17" stroke="#2c3e50" stroke-width="1.5" stroke-linecap="round"/>
              <line x1="20" y1="11" x2="28" y2="11" stroke="rgba(44,62,80,0.5)" stroke-width="2" stroke-linecap="round"/>
              <line x1="20" y1="16" x2="25" y2="16" stroke="rgba(44,62,80,0.4)" stroke-width="1.7" stroke-linecap="round"/>
            </g>
          </svg>
        </div>
        <h1 class="brand-title">智慧图书馆</h1>
        <p class="brand-subtitle">Library Management System</p>
      </div>

      <div class="login-card" :class="{ 'mode-switch': isRegister }">
        <transition name="view-fade" mode="out-in">
          <div v-if="!isRegister" key="login" class="form-view">
            <div class="card-header">
              <h2>欢迎回来</h2>
              <p>请输入账号信息以继续</p>
            </div>

            <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" size="large"
                     @keyup.enter="handleLogin" class="auth-form">
              <el-form-item prop="username">
                <el-input v-model="loginForm.username" placeholder="用户名" :prefix-icon="User" />
              </el-form-item>

              <el-form-item prop="password">
                <el-input v-model="loginForm.password" type="password" placeholder="密码"
                          :prefix-icon="Lock" show-password />
              </el-form-item>

              <el-form-item prop="captchaCode">
                <div class="captcha-row">
                  <el-input v-model="loginForm.captchaCode" placeholder="验证码" :prefix-icon="Key" maxlength="4"
                            @keyup.enter="handleLogin" class="captcha-input" />
                  <div class="captcha-img-wrap" @click="refreshCaptcha" title="点击刷新验证码">
                    <img v-if="captchaImage" :src="'data:image/png;base64,' + captchaImage" alt="captcha" class="captcha-img" />
                    <div v-else class="captcha-loading">加载中</div>
                    <span class="refresh-hint"><svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M13.65 2.35A8 8 0 102.35 13.65M13.65 2.35L9 7M13.65 2.35l-3.5-.5m3.5.5l.5 3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
                  </div>
                </div>
              </el-form-item>

              <el-form-item>
                <button type="button" class="submit-btn" :class="{ loading: loading }"
                        @click="handleLogin" :disabled="loading">
                  <span v-if="loading" class="btn-spinner"></span>
                  {{ loading ? '登录中...' : '登 录' }}
                </button>
              </el-form-item>
            </el-form>

            <div class="switch-mode">
              <span>忘记密码请联系管理员。 </span>
              <span>还没有账号？</span>
              <a href="javascript:;" @click="switchToRegister">立即注册</a>
            </div>
          </div>

          <div v-else key="register" class="form-view">
            <div class="card-header">
              <h2>创建账号</h2>
              <p>注册成为读者，享受借阅服务</p>
            </div>

            <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" size="large"
                     @keyup.enter="handleRegister" class="auth-form">
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item prop="username"><el-input v-model="registerForm.username" placeholder="用户名" :prefix-icon="User" /></el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item prop="realName"><el-input v-model="registerForm.realName" placeholder="真实姓名" :prefix-icon="UserFilled" /></el-form-item>
                </el-col>
              </el-row>
              <el-form-item prop="password">
                <el-input v-model="registerForm.password" type="password" placeholder="密码（至少6位）" :prefix-icon="Lock" show-password />
              </el-form-item>
              <el-form-item prop="confirmPassword">
                <el-input v-model="registerForm.confirmPassword" type="password" placeholder="确认密码" :prefix-icon="Lock" show-password />
              </el-form-item>
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item prop="phone"><el-input v-model="registerForm.phone" placeholder="手机号（选填）" :prefix-icon="Phone" /></el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item prop="email"><el-input v-model="registerForm.email" placeholder="邮箱（选填）" :prefix-icon="Message" /></el-form-item>
                </el-col>
              </el-row>
              <el-form-item>
                <button type="button" class="submit-btn submit-btn-reg" :class="{ loading: loading }"
                        @click="handleRegister" :disabled="loading">
                  <span v-if="loading" class="btn-spinner"></span>
                  {{ loading ? '注册中...' : '注 册' }}
                </button>
              </el-form-item>
            </el-form>

            <div class="switch-mode">
              <span>已有账号？</span>
              <a href="javascript:;" @click="switchToLogin">返回登录</a>
            </div>
          </div>
        </transition>
      </div>

      <footer class="page-footer">
        <p>&copy; {{ currentYear }} 智慧图书馆管理系统</p>
      </footer>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { User, Lock, Key, UserFilled, Phone, Message } from '@element-plus/icons-vue'
import { loginApi, registerApi, getCaptchaImage } from '@/api/auth'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

onMounted(() => {
  userStore.logout()
  refreshCaptcha()
})

const currentYear = computed(() => new Date().getFullYear())
const markHover = ref(false)
const isRegister = ref(false)
const loading = ref(false)
const captchaImage = ref('')

const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '', captchaCode: '' })
const captchaKey = ref('')
const registerForm = reactive({
  username: '', password: '', confirmPassword: '',
  realName: '', phone: '', email: '',
})

async function refreshCaptcha() {
  try {
    const res = await getCaptchaImage()
    captchaImage.value = res?.image || ''
    captchaKey.value = res?.captchaKey || ''
  } catch {
    captchaImage.value = ''
    captchaKey.value = ''
  }
}

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不少于6位', trigger: 'blur' },
  ],
  captchaCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { len: 4, message: '验证码为4位字符', trigger: 'blur' },
  ],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名3-20个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不少于6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== registerForm.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [{ pattern: /^$|1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

function switchToRegister() { isRegister.value = true }
function switchToLogin() { isRegister.value = false }

async function handleLogin() {
  if (loading.value || !loginFormRef.value) return
  try { await loginFormRef.value.validate() } catch { return }

  if (!loginForm.captchaCode || loginForm.captchaCode.length !== 4) {
    ElMessage.warning('请输入4位验证码')
    return
  }

  loading.value = true
  try {
    const data = await loginApi({ ...loginForm, captchaKey: captchaKey.value })
    if (!data.token || !data.userInfo) { ElMessage.error('登录响应数据异常'); return }
    userStore.setToken(data.token)
    userStore.setUserInfo(data.userInfo)
    ElMessage.success(`欢迎回来，${data.userInfo.realName || data.userInfo.username}`)
    const redirect = route.query.redirect as string | undefined
    await router.push(redirect || '/')
  } catch (error: unknown) {
    if (error instanceof Error) {
      ElMessage.error(error.message || '登录失败')
      refreshCaptcha()
      loginForm.captchaCode = ''
    }
  } finally { loading.value = false }
}

async function handleRegister() {
  if (!registerFormRef.value) return
  try { await registerFormRef.value.validate() } catch { return }
  loading.value = true
  try {
    await registerApi({
      username: registerForm.username,
      password: registerForm.password,
      realName: registerForm.realName,
      phone: registerForm.phone || undefined,
      email: registerForm.email || undefined,
    })
    ElMessage.success('注册成功，请登录')
    loginForm.username = registerForm.username
    loginForm.password = ''
    loginForm.captchaCode = ''
    isRegister.value = false
    refreshCaptcha()
  } catch (error: unknown) {
    if (error instanceof Error) ElMessage.error(error.message || '注册失败')
  } finally { loading.value = false }
}

</script>

<style lang="scss" scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.bg-layer {
  position: absolute;
  inset: 0;
  z-index: 0;

  .bg-image {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center center;
  }

  .bg-overlay {
    position: absolute;
    inset: 0;
    background: linear-gradient(135deg, rgba(44, 62, 80, 0.7) 0%, rgba(52, 73, 94, 0.6) 100%);
    backdrop-filter: blur(2px);
  }
}

.login-container {
  position: relative;
  z-index: 2;
  width: 100%;
  max-width: 440px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.login-brand {
  text-align: center;
  margin-bottom: 32px;

  .brand-mark {
    display: inline-flex;
    margin-bottom: 18px;
    cursor: default;
    transition: transform 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);

    &:hover, &.hovered {
      transform: scale(1.08) rotate(-2deg);

      svg rect[fill*="0.15"] { fill: rgba(255,255,255,0.22); }
    }
  }

  .brand-title {
    font-size: 28px;
    font-weight: 700;
    color: #fff;
    letter-spacing: 4px;
    margin-bottom: 6px;
    text-shadow: 0 2px 16px rgba(0,0,0,0.25);
  }

  .brand-subtitle {
    font-size: 13px;
    color: rgba(255,255,255,0.6);
    letter-spacing: 2px;
    font-weight: 400;
    text-transform: uppercase;
  }
}

.login-card {
  background: rgba(255,255,255,0.96);
  backdrop-filter: blur(20px);
  border-radius: 18px;
  padding: 40px 32px 32px;
  box-shadow:
    0 10px 40px rgba(0,0,0,0.15),
    0 2px 10px rgba(0,0,0,0.06),
    inset 0 1px 0 rgba(255,255,255,0.7);
  width: 100%;
  transition: box-shadow 0.4s ease, transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);

  &:hover {
    box-shadow:
      0 16px 56px rgba(0,0,0,0.18),
      0 4px 14px rgba(0,0,0,0.08),
      inset 0 1px 0 rgba(255,255,255,0.8);
  }
}

.card-header {
  margin-bottom: 28px;

  h2 {
    font-size: 20px;
    font-weight: 700;
    color: #2c3e50;
    margin-bottom: 6px;
    letter-spacing: 0.3px;
  }

  p {
    font-size: 14px;
    color: #7f8c8d;
  }
}

.auth-form {
  :deep(.el-form-item) {
    margin-bottom: 20px;
  }

  :deep(.el-input__wrapper) {
    border-radius: 10px;
    padding: 5px 14px;
    box-shadow: 0 0 0 1px #e0e6ed inset;
    transition: all 0.28s cubic-bezier(0.4, 0, 0.2, 1);
    background: #f8f9fa;

    &:hover {
      box-shadow: 0 0 0 1px #cbd5e0 inset;
      background: #fff;
    }

    &.is-focus {
      box-shadow: 0 0 0 1.5px #2c3e50 inset, 0 0 0 4px rgba(44,62,80,0.08);
      background: #fff;
    }

    .el-input__inner {
      height: 44px;
      font-size: 14px;
      letter-spacing: 0.2px;
    }

    .el-input__prefix {
      color: #a0aec0;
    }
  }

  .captcha-row {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;

    .captcha-input {
      flex: 1;
    }

    .captcha-img-wrap {
      width: 120px;
      height: 46px;
      border-radius: 10px;
      overflow: hidden;
      cursor: pointer;
      position: relative;
      flex-shrink: 0;
      border: 1px solid #e0e6ed;
      background: #fff;
      transition: border-color 0.22s ease, transform 0.22s ease;
      display: flex;
      align-items: center;
      justify-content: center;

      &:hover {
        border-color: #2c3e50;
        transform: scale(1.04);

        .refresh-hint {
          opacity: 1;
        }
      }

      &:active {
        transform: scale(0.96);
      }

      .captcha-img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }

      .captcha-loading {
        font-size: 11px;
        color: #cbd5e0;
      }

      .refresh-hint {
        position: absolute;
        top: 50%; left: 50%;
        transform: translate(-50%, -50%);
        background: rgba(44,62,80,0.75);
        color: #fff;
        border-radius: 6px;
        padding: 4px 8px;
        opacity: 0;
        transition: opacity 0.2s ease;
        pointer-events: none;
        display: flex;
        align-items: center;
        justify-content: center;
      }
    }
  }

  .submit-btn {
    width: 100%;
    height: 50px;
    border-radius: 12px;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 3px;
    background: linear-gradient(135deg, #2c3e50 0%, #34495e 100%);
    color: #fff;
    border: none;
    cursor: pointer;
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    position: relative;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;

    &:hover:not(:disabled) {
      background: linear-gradient(135deg, #34495e 0%, #2c3e50 100%);
      transform: translateY(-2px);
      box-shadow: 0 8px 24px rgba(44,62,80,0.35);
    }

    &:active { transform: translateY(0); }

    &:disabled { opacity: 0.6; cursor: not-allowed; }

    &.submit-btn-reg {
      background: linear-gradient(135deg, #16a085 0%, #1abc9c 100%);
      &:hover:not(:disabled) {
        background: linear-gradient(135deg, #1abc9c 0%, #16a085 100%);
        box-shadow: 0 8px 24px rgba(22,160,133,0.35);
      }
    }

    .btn-spinner {
      width: 18px; height: 18px;
      border: 2px solid rgba(255,255,255,0.3);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin 0.6s linear infinite;
    }
  }
}

@keyframes spin { to { transform: rotate(360deg); } }

.switch-mode {
  text-align: center;
  margin-top: 22px;
  font-size: 13.5px;
  color: #95a5a6;

  .divider {
    margin: 0 8px;
    color: #cbd5e0;
  }

  .forgot-link {
    color: #e67e22;
    font-weight: 500;

    &::after { background: #e67e22; }

    &:hover { color: #d35400; }
  }

  a {
    color: #2c3e50;
    text-decoration: none;
    font-weight: 600;
    margin-left: 4px;
    position: relative;
    transition: color 0.25s;

    &::after {
      content: '';
      position: absolute;
      bottom: -2px;
      left: 0;
      width: 0;
      height: 1.5px;
      background: #2c3e50;
      transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    }

    &:hover {
      color: #34495e;

      &::after { width: 100%; }
    }
  }
}

.page-footer {
  text-align: center;
  margin-top: 28px;

  p {
    font-size: 12.5px;
    color: rgba(255,255,255,0.5);
    letter-spacing: 0.5px;
  }
}

.view-fade-enter-active,
.view-fade-leave-active {
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}
.view-fade-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.view-fade-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

@media (max-width: 480px) {
  .login-container { padding: 18px; }
  .login-card { padding: 32px 24px 26px; border-radius: 16px; }
  .brand-title { font-size: 24px; letter-spacing: 3px; }
  .card-header h2 { font-size: 18px; }
  .auth-form .captcha-row .captcha-img-wrap { width: 100px; height: 42px; }
}
</style>
