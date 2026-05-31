import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/**
 * Flat-config ESLint setup for the plugin-vm Vue bundle. Mirrors the
 * host's eslint.config.js so plugin code follows the same rules.
 */
export default [
  {
    ignores: [
      'node_modules/**',
      // Built bundle is emitted into the maven module's resources dir.
      '../src/main/resources/META-INF/resources/webjars/vm/vue/**',
    ],
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,cjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/valid-v-slot': ['error', { allowModifiers: true }],
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
  },
]
