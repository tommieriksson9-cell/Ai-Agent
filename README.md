# ⚡ AI Agent Android App

A native Android AI agent powered by Claude. Ask questions and the agent automatically uses tools to answer.

## 🛠 Tools Available
- ⚡ **Calculator** — math expressions
- 🌡️ **Unit Converter** — temperature, length, weight
- 📊 **Word Counter** — words, chars, sentences
- ✨ **Text Transformer** — uppercase, reverse, titlecase
- **{}** **JSON Formatter** — parse & validate JSON

---

## 🚀 Setup & Build

### Option A: Build with GitHub Actions (No Android Studio needed)

1. **Fork this repo** on GitHub

2. **Add your API key as a GitHub Secret:**
   - Go to your repo → Settings → Secrets and variables → Actions
   - Click "New repository secret"
   - Name: `ANTHROPIC_API_KEY`
   - Value: `sk-ant-your-key-here`
   - Get your key at: https://console.anthropic.com

3. **Trigger a build:**
   - Go to Actions tab → "Build Android APK" → Run workflow
   - Or just push any commit to `main`

4. **Download your APK:**
   - Go to Actions → click the latest run → scroll to Artifacts
   - Download `ai-agent-debug` → install on your phone!

---

### Option B: Build locally with Android Studio

1. Clone the repo
2. Create `local.properties` in the root:
   ```
   ANTHROPIC_API_KEY=sk-ant-your-key-here
   sdk.dir=/path/to/your/android/sdk
   ```
3. Open in Android Studio → Run ▶️

---

## 📁 Project Structure
```
AIAgentApp/
├── app/src/main/
│   ├── java/com/aiagent/app/
│   │   ├── MainActivity.kt       # UI controller
│   │   ├── AgentViewModel.kt     # Agent loop + tools + Claude API
│   │   └── ChatAdapter.kt        # RecyclerView adapter
│   └── res/
│       ├── layout/               # XML layouts
│       └── values/               # Colors, themes, strings
├── .github/workflows/build.yml   # GitHub Actions CI
└── README.md
```

## ⚠️ Security Note
- Never commit `local.properties` (it's in `.gitignore`)
- Use GitHub Secrets for CI builds
- For production, proxy API calls through your own backend
