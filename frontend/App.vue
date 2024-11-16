<script>
	export default {
		data() {
			return {
				selectedCategory: null // 用于存储传递的 category 参数
			};
		},
		onLaunch: function() {
			console.log('App Launch')
		},
		onShow: function() {
			console.log('App Show')
		},
		onHide: function() {
			console.log('App Hide')
		}
	}
</script>

<style>
	/*每个页面公共css */
</style>
