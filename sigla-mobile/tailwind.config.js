/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./App.{js,jsx,ts,tsx}",
    "./src/**/*.{js,jsx,ts,tsx}"
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: '#13306B',
          logo: '#1A387E',
          text: '#13306B',
          tint: '#EEF3FC',
          dark: '#C9D8FA',
          darkText: '#B8CCF8'
        },
        sg: {
          brand: '#13306B',
          logoBlue: '#1A387E',
          brandText: '#13306B',
          bg: '#FFFFFF',
          tint: '#EEF3FC',
          barBg: '#FFFFFF',
          text: '#0F1B33',
          textSecondary: '#626B7E',
          divider: 'rgba(19, 48, 107, 0.08)',
          danger: '#E53935',
          success: '#2E7D32',
          onBrand: '#FFFFFF',
          onBrandSecondary: 'rgba(255, 255, 255, 0.8)',
          onDark: '#FFFFFF',
          onDarkSecondary: 'rgba(255, 255, 255, 0.8)',
          navBar: '#0D1127',
          illusSoft: '#C9D8FA',
          illusMid: '#9DB8F2',
          // Night palette
          darkBg: '#0C1430',
          darkBrand: '#C9D8FA',
          darkBrandText: '#B8CCF8',
          darkTint: '#172350',
          darkBarBg: '#111B3E',
          darkText: '#EEF3FC',
          darkTextSecondary: '#A9B6D6',
          darkDivider: 'rgba(255, 255, 255, 0.08)',
          darkDanger: '#EF5350',
          darkSuccess: '#4CAF50',
          darkOnBrand: '#13306B',
          darkOnBrandSecondary: 'rgba(19, 48, 107, 0.8)',
          darkIllusSoft: '#233A73',
          darkIllusMid: '#6F8FD6'
        }
      },
      borderRadius: {
        'sg-sm': '16px',
        'sg-md': '20px',
        'sg-lg': '24px'
      },
      spacing: {
        'sg-4': '4px',
        'sg-8': '8px',
        'sg-12': '12px',
        'sg-16': '16px',
        'sg-20': '20px',
        'sg-24': '24px',
        'sg-32': '32px',
        'sg-screen': '20px'
      }
    }
  },
  plugins: []
};
