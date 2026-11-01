<script setup lang="ts">
const icons: Record<string, string[]> = {
  home: ["M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"],
  monitor: ["M3 4h18v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M8 20h8M12 16v4"],
  user: ["M12 8a4 4 0 1 0 0.01 0", "M4 21c1-4 4.5-6 8-6s7 2 8 6"],
  briefcase: [
    "M4 7h16v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z",
    "M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2",
  ],
  image: ["M3 3h18v18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M9 9a2 2 0 1 0 0.01 0", "M21 15l-5-5L5 21"],
  images: ["M4 16l4-4 3 3 5-5 4 4", "M3 4h18v16a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"],
  calendar: ["M3 5h18v16a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M3 10h18M8 3v4M16 3v4"],
  clock: ["M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 7v5l3 2"],
  bell: ["M6 8a6 6 0 1 1 12 0c0 7 3 8 3 8H3s3-1 3-8", "M10 20a2 2 0 0 0 4 0"],
  upload: ["M12 3v12M7 8l5-5 5 5", "M5 15v4a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4"],
  chevron: ["M6 9l6 6 6-6"],
  logout: ["M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3", "M10 17l-5-5 5-5M5 12h11"],
  plus: ["M12 5v14M5 12h14"],
  star: ["M12 3.5l2.2 4.6 5.1.7-3.7 3.6.9 5.1L12 15.2 7.5 17.5l.9-5.1L4.7 8.8l5.1-.7z"],
  close: ["M6 6l12 12M18 6L6 18"],
  search: ["M11 11m-7 0a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M21 21l-4-4"],
  alert: [
    "M12 9v4M12 17h.01",
    "M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
  ],
  phone: ["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2"],
};

defineProps<{ name: string }>();
</script>

<template>
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path v-for="(d, index) in icons[name] ?? []" :key="index" :d="d" />
  </svg>
</template>
