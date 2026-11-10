import { clientConfig } from '../shared/vite-config'

export default clientConfig(new URL('.', import.meta.url), '/student/', 5174)
