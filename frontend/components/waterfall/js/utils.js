export const wait = (time = 100)=>{
	return new Promise((resolve)=>{
		setTimeout(()=>{
			resolve();
		},time);
	})
}