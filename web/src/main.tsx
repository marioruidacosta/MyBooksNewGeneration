import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './boot'
import '@fontsource-variable/literata/wght.css'
import '@fontsource-variable/literata/wght-italic.css'
import './theme.css'
import { App } from './App'
import { applyTheme, loadTheme } from './appearance'

applyTheme(loadTheme())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
