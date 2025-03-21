# Swanbar

A custom Sway title bar written in Clojure and compiled with GraalVM. 

# Why? 

I don't know.  I had a custom title bar written with Bash that did all this stuff, but it got messy.  The protocol for the Sway is pretty easy, and so I thought it might be fun to overengineer the hell out of it. 

And overengineer I did!  I used `core.async` liberally to minimize blocking, and I have plans...plans I tell you! 

# Setup

This is a basic multimethod framework which is fairly customizable. 

For any module you want to create, you need to make implement two methods: `fetchdata` and `render`.  

`render` *must* be blocking, however fetchdata can be asynchronous.  Perform your non-blocking stuff in a fetch-data block if possible. 

Your implementation should look something like 

```
(defmethod fetch-data :my-module [_ timeout] 
    {:data {}}
)
```

This object can be arbitrary, it just needs to have all the information you care about for rendering. If you would like to do an async operation (e.g. call a web API that you don't want to block other stuff), return the same object on a channel, e.g.:

```
(defmethod fetch-data :my-module [_ timeout] 
    (go {:data {}})
)
```


Your render function should look something like this: 

```
(defmethod render :my-module [_ render-data]
  {:out (str (:item render-data))})
```

The `:out` value *must* be a string. Otherwise there are no limitations.  The `render-data` is the `:data` from the `fetch-data` function. 


You will also need a JSON configuration file.  

The file will be one big ol' array of objects.  

```
{
	"poll_time" : 50,
	"modules" : [
		{
			"async": true,
			"async_timeout": 1000,
			"name": "quote",
			"ttl": 480000,
			"color": "#EEEEEE",
			"background": "#222222"
		},
		{
			"name": "volume",
			"ttl": 0, 
			"click_program": "pavucontrol"
		}
	]
}

```

All fields other than `name` are optional. `name` *must* match the keyword used in your multimethods. 

Async functions must be explicitly labels (for now). The `async_timeout` parameter is the amount of time we will wait for a job before killing it and retrying. 

`ttl` is minimum the amount of time you want to wait between calls to your `fetch_data` function.  This is useful if you are, for example, making a call to an HTTP api that you do not want to hit on every iteration of the loop. Look at the `:quote` `fetch-data` function for an example of where this is used. 

You must then pass in this JSON as a parameter when launching the swaybar. 


```
bar {
    position top 
    status_command  ~/.config/sway/swaybar2 ~/.config/sway/swaybar-config.json
}
```
