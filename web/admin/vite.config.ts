import { clientConfig } from '../shared/vite-config'

export default clientConfig(new URL('.', import.meta.url), '/admin/', 5173)
