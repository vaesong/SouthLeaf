<template>
  <div class="preview-col">
    <p>
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
      >
        <rect x="7" y="2" width="10" height="20" rx="2" />
      </svg>
      <slot name="caption">小程序预览（草稿）</slot>
    </p>
    <div class="phone">
      <div class="screen">
        <div class="screen-scroll">
          <div class="mp-status"><span>9:41</span><span>预览</span></div>
          <slot />
        </div>
      </div>
    </div>
  </div>
</template>
